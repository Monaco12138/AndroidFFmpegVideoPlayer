# AndroidFFmpegVideoPlayer

这是一个集成了超分增强的视频播放器，使用了ffmpeg进行拉流，将得到的视频流超分增强并渲染展示出来。
该项目使用Android NDK开发，集成ffmpeg方法，可以获取拉流完后的每一帧视频数据，方便后续部署属于你的神经网络模型。具体如何训练，量化导出测试自定义的模型可以见[此仓库](https://github.com/Monaco12138/SR_Tensorflow)