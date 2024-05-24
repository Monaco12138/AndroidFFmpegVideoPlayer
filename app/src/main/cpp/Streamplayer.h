//
// Created by chaibli on 2024/5/21.
//

#ifndef FFMPEGVIDEOPLAYER_STREAMPLAYER_H
#define FFMPEGVIDEOPLAYER_STREAMPLAYER_H


extern  "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
}
#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include <stdlib.h>
#include <chrono>
#include <android/log.h>

#define LOG_TAG "MyNativeCode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

class StreamPlayer {
public:
    JNIEnv * env;
    jclass cls;
    jmethodID funcMethod;

    AVFormatContext* deFormatc;
    AVCodecContext* deCodecc;
    int video_index;
    int frame_decoded_count;

    StreamPlayer(JavaVM* javaVM, jstring url) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) {
            LOGI("Failed to attach current thread");
            throw std::runtime_error("Failed to attach current thread");
        }
        cls = env->FindClass("com/example/ffmpegvideoplayer/MainActivity");
        if (cls == nullptr) {
            LOGI("Failed to find class MainActivity");
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            javaVM->DetachCurrentThread();
            throw std::runtime_error("Failed to find class MainActivity");
        }
        this->funcMethod = env->GetStaticMethodID(cls, "putData", "([I)V");

        this->frame_decoded_count = 0;
        this->deFormatc = createFormatc(url);
        this->deCodecc = createCodecc(this->deFormatc);
    }

    AVFormatContext* createFormatc(jstring url) {
        const char* video_address = env->GetStringUTFChars(url, nullptr);
        LOGI("%s", video_address);
        // create decoder
        AVFormatContext* av_formatc = avformat_alloc_context();
        if (!av_formatc) {
            LOGI("Failed to alloc memory for avformat");
            throw std::runtime_error("Failed to alloc memory for avformat");
        }
        // setting params
        AVDictionary* opts = nullptr;
        av_dict_set(&opts, "rtsp_transport", "tcp", 0);
        // open video
        int ret = avformat_open_input(&av_formatc, video_address, nullptr, &opts);
        if (ret != 0) {
            LOGI("Failed to open input file");
            throw std::runtime_error("Failed to open input file");
        }
        // find the input stream
        // it will be blocked if broadcaster doesn't send the stream
        LOGI("Waiting for the stream ...");
        ret = avformat_find_stream_info(av_formatc, nullptr);
        if (ret != 0) {
            LOGI("Failed to get stream info");
            throw std::runtime_error("Failed to get stream info");
        }
        return av_formatc;
    }

    AVCodecContext* createCodecc(AVFormatContext* avFormatc) {
        if (avFormatc == nullptr) {
            LOGI("de_formatc is nullptr!");
            throw std::runtime_error("de_formatc is nullptr!");
        }
        AVStream* de_stream = nullptr;
        // find stream index
        for (int i = 0; i < avFormatc->nb_streams; i++) {
            if (avFormatc->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                video_index = i;
                de_stream = avFormatc->streams[i];
                break;
            }
        }
        // find de_codec by codec_id
        const AVCodec* de_codec = avcodec_find_decoder(de_stream->codecpar->codec_id);
        if (!de_codec) {
            LOGI("Failed to find the de_codec");
            throw std::runtime_error("Failed to find the de_codec");
        }
        // use the de_codec to create de_codec_context
        AVCodecContext* de_codecc = avcodec_alloc_context3(de_codec);
        if (!de_codecc) {
            LOGI("Failed to alloc memory for de_codec context");
            throw std::runtime_error("Failed to alloc memory for de_codec context");
        }
        // copy the params from de_stream to de_codec_context
        int ret = avcodec_parameters_to_context(de_codecc, de_stream->codecpar);
        if (ret < 0) {
            LOGI("Failed to copy the params to de_codec context");
            throw std::runtime_error("Failed to copy the params to de_codec context");
        }
        de_codecc->thread_count = 16;
        ret = avcodec_open2(de_codecc, de_codec, nullptr);
        if (ret < 0) {
            LOGI("Failed to open de_codecc");
            throw std::runtime_error("Failed to open de_codecc");
        }
        LOGI("Successfully initial AVCodecContext");
        return de_codecc;
    }

    void start() {
        AVPacket* input_packet = av_packet_alloc();
        int packet_num = 0;
        bool stop = false;
        while (!stop) {
            int ret = av_read_frame(this->deFormatc, input_packet);
            if (ret < 0) {
                LOGI("Receiving ret < 0!");
                stop = true;
            } else {
                // skip audio stream, just process video stream
                if (input_packet->stream_index != this->video_index) {
                    continue;
                }
                ret = decoding(input_packet);
                if (ret < 0) {
                    LOGI("Decoding Error");
                }
                packet_num++;
            }
            av_packet_unref(input_packet);
        }
        // flush decoder
        int ret = decoding(nullptr);
        if (ret < 0) {
            LOGI("Flush decoder Error");
        }
        av_packet_free(&input_packet);
        LOGI("Receiving done!");
    }

    int decoding(AVPacket* received_packet) {
        AVFrame* input_frame = av_frame_alloc();
        int ret = avcodec_send_packet(this->deCodecc, received_packet);
        if (ret < 0) {
            LOGI("Error while sending packet to decoder");
            return -1;
        }
        while (ret >= 0) {
            ret = avcodec_receive_frame(this->deCodecc, input_frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            } else if (ret < 0) {
                LOGI("Error while receiving frame from decoder");
                return ret;
            }

            auto startTime = std::chrono::high_resolution_clock::now();
            ret = avFrameYUV420ToARGB8888(input_frame);
            auto endTime = std::chrono::high_resolution_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);
            LOGI("avFrameYUV420ToARGB8888 cost Time = %f ms", (double)(duration.count()) );

            if (ret < 0) {
                LOGI("Error! avFrameYUV420ToARGB8888");
            }
            frame_decoded_count++;
            LOGI("frame decoded count %d, wdith = %d, height = %d", frame_decoded_count, input_frame->width, input_frame->height);

            av_frame_unref(input_frame);
        }
        av_frame_free(&input_frame);

        return 0;
    }

    // AVFrame 的内存布局
    /*             <------------Y-linesize----------->
      *             <-------------width------------>
      *             -----------------------------------
      *             |                              |  |
      *             |                              |  |
      *   height    |              Y               |  |
      *             |                data[0]       |  |
      *             |                              |  |
      *             |                              |  |
      *             -----------------------------------
      *             |             |  |             |  |
      * height / 2  |      U      |  |      V      |  |
      *             |    data[1]  |  | data[2]     |  |
      *             -----------------------------------
      *             <---U-linesize--> <--V-linesize--->
      *             <---U-width--->   <--V-width--->
  */
    int avFrameYUV420ToARGB8888(AVFrame* frame) {
        int width = frame->width;
        int height = frame->height;
        jintArray outFrame = env->NewIntArray(width * height);
        if (outFrame == nullptr) {
            LOGI("Failed to allocate memory");
            return -1;
        }

        jint* outData = env->GetIntArrayElements(outFrame, nullptr);
        if (outData == nullptr) {
            LOGI("outData is nullptr");
            return -1;
        }

        // process
//        int thread_num = 2;
//        std::vector<std::thread> process_thread(thread_num);
//        for (int th = 0; th < thread_num; th++) {
//            process_thread[th] = std::thread(
//                        [&frame, &outData, width, height, th, thread_num]() {
//                            int yp = (height / thread_num) * width * th;
//                            int endIn = th < (thread_num - 1) ? (height / thread_num) * th + (height / thread_num) : height;
//                            for (int j = (height / thread_num) * th; j < endIn; j++) {
//                                int pY = frame->linesize[0] * j;
//                                int pU = (frame->linesize[1]) * (j >> 1);
//                                int pV = (frame->linesize[2]) * (j >> 1);
//                                for (int i = 0; i < width; i++) {
//                                    int yData = frame->data[0][pY + i];
//                                    int uData = frame->data[1][pU + (i >> 1)];
//                                    int vData = frame->data[2][pV + (i >> 1)];
//                                    outData[yp++] = YUV2RGB(0xff & yData, 0xff & uData, 0xff & vData);
//                                }
//                            }
//                        }
//                    );
//        }
//        for (auto& th: process_thread) th.join();

//        process_thread[0] = std::thread(
//                [&frame, &outData, width, height]() {
//                    int yp = 0;
//                    for (int j = 0; j < height / 2; j++) {
//                        int pY = frame->linesize[0] * j;
//                        int pU = (frame->linesize[1]) * (j >> 1);
//                        int pV = (frame->linesize[2]) * (j >> 1);
//                        for (int i = 0; i < width; i++) {
//                            int yData = frame->data[0][pY + i];
//                            int uData = frame->data[1][pU + (i >> 1)];
//                            int vData = frame->data[2][pV + (i >> 1)];
//                            outData[yp++] = YUV2RGB(0xff & yData, 0xff & uData, 0xff & vData);
//                        }
//                    }
//                }
//            );
//
//        process_thread[1] = std::thread(
//                [&frame, &outData, width, height]() {
//                    int yp = (height / 2) * width;
//                    for (int j = height / 2; j < height; j++) {
//                        int pY = frame->linesize[0] * j;
//                        int pU = (frame->linesize[1]) * (j >> 1);
//                        int pV = (frame->linesize[2]) * (j >> 1);
//                        for (int i = 0; i < width; i++) {
//                            int yData = frame->data[0][pY + i];
//                            int uData = frame->data[1][pU + (i >> 1)];
//                            int vData = frame->data[2][pV + (i >> 1)];
//                            outData[yp++] = YUV2RGB(0xff & yData, 0xff & uData, 0xff & vData);
//                        }
//                    }
//                }
//        );
//        for (auto& th: process_thread) th.join();

        int yp = 0;
        for (int j = 0; j < height; j++) {
            int pY = frame->linesize[0] * j;
            int pU = (frame->linesize[1]) * (j >> 1);
            int pV = (frame->linesize[2]) * (j >> 1);
            for (int i = 0; i < width; i++) {
                int yData = frame->data[0][pY + i];
                int uData = frame->data[1][pU + (i >> 1)];
                int vData = frame->data[2][pV + (i >> 1)];
                outData[yp++] = YUV2RGB(0xff & yData, 0xff & uData, 0xff & vData);
            }
        }

        env->ReleaseIntArrayElements(outFrame, outData, 0);
        env->CallStaticVoidMethod(this->cls, this->funcMethod, outFrame);
        env->DeleteLocalRef(outFrame);
        return 0;
    }

    static int YUV2RGB(int y, int u , int v) {
        int kMaxChannelValue = 262143;
        // Adjust and check YUV values
        y = (y - 16) < 0 ? 0 : (y - 16);
        u -= 128;
        v -= 128;
        // This is the floating point equivalent. We do the conversion in integer
        // because some Android devices do not have floating point in hardware.
        // nR = (int)(1.164 * nY + 1.596 * nV);
        // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
        // nB = (int)(1.164 * nY + 2.018 * nU);
        // 这里取的系数为1024，两边都同时乘上1024
        int y1192 = 1192 * y;
        int r = (y1192 + 1634 * v);
        int g = (y1192 - 833 * v - 400 * u);
        int b = (y1192 + 2066 * u);
        // Clipping RGB values to be inside boundaries [ 0 , kMaxChannelValue ]
        // KMaxChannelValue = 262143 即 2^18，原始范围应该限制在[0,255]之间，由于换成整数乘了1024故在[0,2^18]
        r = r > kMaxChannelValue ? kMaxChannelValue : (r < 0 ? 0 : r);
        g = g > kMaxChannelValue ? kMaxChannelValue : (g < 0 ? 0 : g);
        b = b > kMaxChannelValue ? kMaxChannelValue : (b < 0 ? 0 : b);
        // 本来应该是 int rgb = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF); ARGB
        // 但是由于转换时乘了1024，需要除以1024，所以是如下的表达式
        return 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
    }
};


#endif //FFMPEGVIDEOPLAYER_STREAMPLAYER_H
