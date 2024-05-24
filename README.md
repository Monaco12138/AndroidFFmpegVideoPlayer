# The is a video player build by ffmepg for android

经过测试，不同的代理部署模型对不同的tflite 量化有着不同的加速效果

在Xiaomi12s 上测的 tflite float32 动态量化 + nnapi 代理提速明显

在PICO4 上测的 gpu代理提速明显 

## TODO
测试PICO4 上 tflite int8 量化 + gpu代理