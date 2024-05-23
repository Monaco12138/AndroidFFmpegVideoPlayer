package com.example.ffmpegvideoplayer.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.widget.Toast;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.TensorProcessor;
import org.tensorflow.lite.support.common.ops.CastOp;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.metadata.MetadataExtractor;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;

public class InferenceTFLite {
    private Interpreter tflite;
    Interpreter.Options options = new Interpreter.Options();
    private String MODEL_FILE = "quicsr_float32_epoch_200.tflite";
    private final Size INPNUT_SIZE = new Size(960, 540);
    private final int[] OUTPUT_SIZE = new int[] {1, 1080, 1920, 3};
    public void initialModel(Context activity) {
        try {
            // 要tflite 2.16.1 版本才支持 Transpose version 6操作
            ByteBuffer tfliteModel = FileUtil.loadMappedFile(activity, MODEL_FILE);
            tflite = new Interpreter(tfliteModel, options);
            Log.i("[Inference TFLite]", "Success loading model");
        } catch (IOException e){
            Log.e("[Inference TFLite]", "Error loading model: ", e);
            Toast.makeText(activity, "load model error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    public int[] getOUTPUT_SIZE() {
        return OUTPUT_SIZE;
    }
    public int[] superResolution(Bitmap bitmap) {
        // Tflite导出默认的量化即可
        TensorImage modelInput = new TensorImage(DataType.FLOAT32);
        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(INPNUT_SIZE.getHeight(), INPNUT_SIZE.getWidth(), ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0, 255))
                .build();

        modelInput.load(bitmap);
        modelInput = imageProcessor.process(modelInput);

        TensorBuffer hwcOutputTensorBuffer;
        hwcOutputTensorBuffer = TensorBuffer.createFixedSize(OUTPUT_SIZE, DataType.FLOAT32);
        if (tflite != null) {
            tflite.run(modelInput.getBuffer(), hwcOutputTensorBuffer.getBuffer());
        }

        int[] outshape = hwcOutputTensorBuffer.getShape();
        // [b, h, w, c]
        int outHeight = outshape[1];
        int outWidth = outshape[2];
        for (int i = 0; i < outshape.length; i++) {
            Log.i("[Inference TFLite]", "output TensorBuffer shape" + i + " " + outshape[i]);
        }

        float[] hwcOutputData = hwcOutputTensorBuffer.getFloatArray();
        int[] pixels = new int[outHeight * outWidth];
        int yp = 0;
        for (int h = 0; h < outHeight; h++) {
            for (int w = 0; w < outWidth; w++) {
                int r = (int) (hwcOutputData[h * outWidth * 3 + w * 3] * 255);
                int g = (int) (hwcOutputData[h * outWidth * 3 + w * 3 + 1] * 255);
                int b = (int) (hwcOutputData[h * outWidth * 3 + w * 3 + 2] * 255);
                r = r > 255 ? 255 : (Math.max(r, 0));
                g = g > 255 ? 255 : (Math.max(g, 0));
                b = b > 255 ? 255 : (Math.max(b, 0));
                pixels[yp++] = 0xff000000 | (r << 16 & 0xff0000) | (g << 8 & 0xff00) | (b & 0xff);
            }
        }
        return  pixels;
    }
    public void addNNApiDelegate() {
        NnApiDelegate nnApiDelegate = null;
        // Initialize interpreter with NNAPI delegate for Android Pie or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//            NnApiDelegate.Options nnApiOptions = new NnApiDelegate.Options();
//            nnApiOptions.setAllowFp16(true);
//            nnApiOptions.setUseNnapiCpu(true);
            //ANEURALNETWORKS_PREFER_LOW_POWER：倾向于以最大限度减少电池消耗的方式执行。这种设置适合经常执行的编译。
            //ANEURALNETWORKS_PREFER_FAST_SINGLE_ANSWER：倾向于尽快返回单个答案，即使这会耗费更多电量。这是默认值。
            //ANEURALNETWORKS_PREFER_SUSTAINED_SPEED：倾向于最大限度地提高连续帧的吞吐量，例如，在处理来自相机的连续帧时。
//            nnApiOptions.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED);
//            nnApiDelegate = new NnApiDelegate(nnApiOptions);
            nnApiDelegate = new NnApiDelegate();
            options.addDelegate(nnApiDelegate);
            Log.i("[Inference TFLite]", "using nnapi delegate.");
        } else {
            addThread(4);
        }
    }

    //    public void addGPUDelegate() {
//        CompatibilityList compatibilityList = new CompatibilityList();
//        if(compatibilityList.isDelegateSupportedOnThisDevice()){
//            GpuDelegate.Options delegateOptions = compatibilityList.getBestOptionsForThisDevice();
//            GpuDelegate gpuDelegate = new GpuDelegate(delegateOptions);
//            options.addDelegate(gpuDelegate);
//            Log.i("Debug tfliteSupport", "using gpu delegate.");
//        } else {
//            addThread(4);
//        }
//    }

    public void addThread(int thread) {
        options.setNumThreads(thread);
        Log.i("[Inference TFLite]", "using addThread: " + thread);
    }
}
