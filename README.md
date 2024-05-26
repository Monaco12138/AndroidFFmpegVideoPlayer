# AndroidFFmpegVideoPlayer

这是一个集成了超分增强的视频播放器，使用了ffmpeg进行拉流，将得到的视频流超分增强并渲染展示出来。
该项目使用Android NDK开发，集成ffmpeg方法，可以获取拉流完后的每一帧视频数据，方便后续部署属于你的神经网络模型。具体如何训练，量化导出测试自定义的模型可以见[此仓库](https://github.com/Monaco12138/SR_Tensorflow)

## 环境配置

下载Android studio, clone本仓库用Android studio打开，确保./app/build.gradle.kts 相关依赖库能正常下载，重新Rebuild Project.

因为拉流是用ffmpeg进行的，我们需要重新源码编译一份适用于Android的ffmpeg，才能使用<libavformat/avformat.h>等库，这里使用[ffmpeg-android-maker](https://github.com/Javernaut/ffmpeg-android-maker)提供的Docker进行编译，将arm64-v8a的结果放在在main/cpp/ffmpeg目录下，如果需要其它的版本，可自行手动编译后替换

## 拉流

定义了Streamplayer类，集成了拉流，拉流过程在C++代码中实现
```C
StreamPlayer player(javaVM, video_address);
player.start();
```

值得注意的是，在C++代码中直接开线程用NDK相关接口调用Java中的函数会有些问题，故我们是采用在Java中开线程调用C++的拉流代码

## 模型推理
这里以超分增强模型为例，模型推理这块放在Java中实现，使用
```Java
BlockingQueue<int[]> rgbBytesQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY)
```
作为通信，C++代码中将得到的avFrame视频帧数据转换为int*的数组后放入调用Java中的接口，将数据放入该队列中，Java中代码从该队列中读数据进行增强处理

__in C++：__
```C++
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
        /* avFrame to jint*
        ...
        */
        
        // call jni to putData
        env->ReleaseIntArrayElements(outFrame, outData, 0);
        env->CallStaticVoidMethod(this->cls, this->funcMethod, outFrame);
        env->DeleteLocalRef(outFrame);
        return 0;
}
```

__in Java:__
``` Java
public static void putData(int[] data) {
        try {
            rgbBytesQueue.put(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
public void ModelInference() {
    new Thread(new Runnable()) {
        @Override
        public void run() {
            while (true) {
                int [] rgbData = rgbBytesQueue.take();
                Bitmap rgbBitmap = Bitmap.createBitmap(outputShape[0], outputShape[1], Bitmap.Config.ARGB_8888);
                rgbBitmap.setPixels(rgbData, 0, outputShape[0], 0, 0, outputShape[0], outputShape[1]);
                /* model inference: input Bitmap, output Bitmap
                ...
                */

                /* output Bitmap to ImageView
                ...
                */
            }
        }
    }
}
```

自此就是一个拉流到模型推理的框架