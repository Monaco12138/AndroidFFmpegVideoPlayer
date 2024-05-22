// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("ffmpegvideoplayer");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("ffmpegvideoplayer")
//      }
//    }

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
#include "Streamplayer.h"

#define LOG_TAG "MyNativeCode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
JavaVM* javaVM;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    javaVM = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ffmpegvideoplayer_MainActivity_stringFromJNI(JNIEnv* env, jobject, jstring message) {
    //std::string hello = "Hello from C++";
    return message;
}

void avFrameYUV420ToARGB8888(AVFrame* frame) {

}

void decoding(AVPacket* received_packet, AVCodecContext* de_codecc) {
    AVFrame* input_frame = av_frame_alloc();

    int ret = avcodec_send_packet(de_codecc, received_packet);
    if (ret < 0) {
        LOGI("Error while sending packet to decoder");
        throw std::runtime_error("Error while sending packet to decoder");
    }

    while (ret >= 0) {
        ret = avcodec_receive_frame(de_codecc, input_frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;
        } else if (ret < 0) {
            LOGI("Error while receiving frame from decoder");
            throw std::runtime_error("Error while receiving frame from decoder");
        }
        avFrameYUV420ToARGB8888(input_frame);
        av_frame_unref(input_frame);
    }
    av_frame_free(&input_frame);
}

void decodeStreamingTest() {
    JNIEnv* env = nullptr;
    if (javaVM->AttachCurrentThread(&env, nullptr) != 0) {
        LOGI("Failed to attach current thread");
    }
    jclass cls = env->FindClass("com/example/ffmpegvideoplayer/MainActivity");
    if (cls == nullptr) {
        LOGI("Failed to find class MainActivity");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        javaVM->DetachCurrentThread();
    }
    jmethodID putDataMethod = env->GetStaticMethodID(cls, "putData", "([I)V");
    LOGI("Start of While(true)");
    while (true) {
        jintArray dataArray = env->NewIntArray(3);
        if (dataArray == nullptr) {
            LOGI("Failed to allocate memory");
            return;
        }

        jboolean isCopy;
        jint* data = env->GetIntArrayElements(dataArray, &isCopy);
        if (isCopy == JNI_TRUE) {
            LOGI("Copy model.");
        } else {
            LOGI("Direct mode.");
        }
        //jint data[3];
        for (int i = 0; i < 3; i++) {
            data[i] = (rand() % 10); // 生成[0, 9]之间的随机数
        }
        LOGI("Calling putData with data: [%d, %d, %d]", data[0], data[1], data[2]);
        //env->SetIntArrayRegion(dataArray, 0, 3, data);
        env->ReleaseIntArrayElements(dataArray, data,0);
        env->CallStaticVoidMethod(cls, putDataMethod, dataArray);
        env->DeleteLocalRef(dataArray);
//        此时data指针已经被释放，当不一定被操作系统回收，应该避免访问
//        LOGI("Calling putData with data: [%d, %d, %d]", data[0], data[1], data[2]);
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    //javaVM->DetachCurrentThread();
}

void decodeStreaming(jstring url) {
    JNIEnv* env = nullptr;
    if (javaVM->AttachCurrentThread(&env, nullptr) != 0) {
        LOGI("Failed to attach current thread");
        throw std::runtime_error("Failed to attach current thread");
    }

    AVFormatContext* de_formatc = nullptr;
    const char* video_address = env->GetStringUTFChars(url, nullptr);
    LOGI("%s", video_address);

    // create decoder
    de_formatc = avformat_alloc_context();
    if (!de_formatc) {
        LOGI("Failed to alloc memory for avformat");
        throw std::runtime_error("Failed to alloc memory for avformat");
    }

    // setting params
    AVDictionary* opts = nullptr;
    av_dict_set(&opts, "rtsp_transport", "tcp", 0);

    // open video
    int ret = avformat_open_input(&de_formatc, video_address, nullptr, &opts);
    if (ret != 0) {
        LOGI("Failed to open input file");
        throw std::runtime_error("Failed to open input file");
    }

    // find the input stream
    // it will be blocked if broadcaster doesn't send the stream
    LOGI("Waiting for the stream ...");
    ret = avformat_find_stream_info(de_formatc, nullptr);
    if (ret != 0) {
        LOGI("Failed to get stream info");
        throw std::runtime_error("Failed to get stream info");
    }

    // ***********************//
    // 创建视频源解码器相关信息
    if (de_formatc == nullptr) {
        LOGI("de_formatc is nullptr!");
        throw std::runtime_error("de_formatc is nullptr!");
    }

    AVStream* de_stream = nullptr;
    AVCodecContext* de_codecc = nullptr;
    int video_index = 0;

    // find stream index
    for (int i = 0; i < de_formatc->nb_streams; i++) {
        if (de_formatc->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_index = i;
            de_stream = de_formatc->streams[i];
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
    de_codecc = avcodec_alloc_context3(de_codec);
    if (!de_codecc) {
        LOGI("Failed to alloc memory for de_codec context");
        throw std::runtime_error("Failed to alloc memory for de_codec context");
    }

    // copy the params from de_stream to de_codec_context
    ret = avcodec_parameters_to_context(de_codecc, de_stream->codecpar);
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

    // receiving
    AVPacket* input_packet = av_packet_alloc();
    int packet_num = 0;
    bool stop = false;
    while (!stop) {
        ret = av_read_frame(de_formatc, input_packet);
        if (ret < 0) {
            LOGI("Receiving ret < 0!");
            stop = true;
        } else {
            // skip audio stream, just process video stream
            if (input_packet->stream_index != video_index) {
                continue;
            }
            decoding(input_packet, de_codecc);
            packet_num++;
        }
        av_packet_unref(input_packet);
    }
}


extern "C" JNIEXPORT void JNICALL
Java_com_example_ffmpegvideoplayer_MainActivity_mainDecoder(JNIEnv* env, jobject instance, jstring url) {
//    decodeStreamingTest();
//    decodeStreaming(url);
    StreamPlayer player(javaVM, url);
    player.start();
}

// .\ffmpeg.exe -stream_loop -1 -i G:\Desktop\206.mp4 -rtsp_transport tcp -f rtsp rtsp://172.18.166.246:8554/mystream
// .\ffmpeg.exe -stream_loop -1 -re -i G:\Desktop\nemo.mp4 -c:v copy -b:v 2000k -framerate 30 -rtsp_transport tcp -f rtsp rtsp://172.18.166.246:8554/mystream