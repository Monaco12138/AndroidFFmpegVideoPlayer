package com.example.ffmpegvideoplayer;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import com.example.ffmpegvideoplayer.analysis.InferenceTFLite;
import android.util.Size;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

public class MainActivity extends AppCompatActivity {

    private static final int QUEUE_CAPACITY = 10;

    // 这个队列不能开太大，如果是 540P的图像数据的话，一个int[540*960]大小大概为2MB
    // 当时设置队列为4096的话程序运行一段时间就炸，因为内存爆了，当程序运行内存超过700MB以后就会Out of memory
    // 所以要设小点
    private static BlockingQueue<int[]> rgbBytesQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static BlockingQueue<TensorImage> modelInputQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static BlockingQueue<TensorBuffer> modelOutputQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static BlockingQueue<int[]> viewOutQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    // width, height
    private static int[] videoShape = new int[] {960, 540};
    private final Size outSize = new Size(1920, 1080);
    static {
        System.loadLibrary("ffmpegvideoplayer");
    }

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ImageView imageView;
    private Handler handler;

    private TextView frameSizeTextView;
    private TextView fpsTextView;
    private boolean isPICO = false;
    private InferenceTFLite srTFLite;
    private int[] outPixels;

    Bitmap inputBitmap;
    Bitmap outputBitmap;

    ImageProcessor imageProcessor;
    private void initModel() {
        try {
            this.srTFLite = new InferenceTFLite();
            this.srTFLite.addNNApiDelegate();
            this.srTFLite.initialModel(this);
        } catch (Exception e) {
            Log.e("Error Exception", "MainActivity initial model error: " + e.getMessage() + e.toString());
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // params
        surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        imageView = findViewById(R.id.imageView);
        handler = new Handler(Looper.getMainLooper());

        outPixels = new int [1920 * 1080];
        inputBitmap = Bitmap.createBitmap(videoShape[0], videoShape[1], Bitmap.Config.ARGB_8888);
        outputBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);

        imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(videoShape[1], videoShape[0], ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0, 255))
                .build();

        initModel();

        // Decoding Threads
        new Thread(new Runnable() {
            @Override
            public void run() {
                mainDecoder(getString(R.string.video_url));
            }
        }).start();

//        ReceiveSRShow2();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
//               ReceiveAndShow();
//                ReceiveSRShow();
                mainProcess();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });
    }
    public void ReceiveAndShow() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        long startTime = System.currentTimeMillis();
                        int [] rgbData = rgbBytesQueue.take();
                        Bitmap rgbBitmap = Bitmap.createBitmap(videoShape[0], videoShape[1], Bitmap.Config.ARGB_8888);
                        rgbBitmap.setPixels(rgbData, 0, videoShape[0], 0, 0, videoShape[0], videoShape[1]);
                        Matrix matrix = new Matrix();
                        if (!isPICO) {
                            matrix.postRotate(90);
                        }
                        Bitmap postTransformImageBitmap = Bitmap.createBitmap(rgbBitmap, 0, 0, videoShape[0], videoShape[1], matrix, false);
                        int outHeight = videoShape[1];
                        int outWidth = videoShape[0];
                        if (isPICO) {
                            outHeight = videoShape[0];
                            outWidth = videoShape[1];
                        }
                        Canvas canvas = surfaceHolder.lockCanvas();
                        if (canvas != null) {
                            try{
                                canvas.drawBitmap(postTransformImageBitmap, null, new Rect(0, 0, outHeight, outWidth), null);
                            } finally {
                                surfaceHolder.unlockCanvasAndPost(canvas);
                            }
                        }
                        long endTime = System.currentTimeMillis();
                        long costTime = endTime - startTime;
                        updateTextView(Long.toString(costTime) + "ms");
                    } catch (InterruptedException e) {
                        Log.e("Error Exception", "MainActivity error: " + e.getMessage() + e.toString());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }).start();
    }

    public void mainProcess() {
        // 从rgbBytesQueue 中 拿数据 转成 模型推理的格式
        preProcess();

        // 从modelInputQueue 中 拿数据推理
        inference();

        // 从modelOutputQueue 中 拿数据转换
        afterProcess();

        // 从viewOutQueue 中 拿数据输出
        viewProcess();
    }

    public void preProcess() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        int[] rgbData = rgbBytesQueue.take();
                        Bitmap rgbBitmap = Bitmap.createBitmap(videoShape[0], videoShape[1], Bitmap.Config.ARGB_8888);
                        rgbBitmap.setPixels(rgbData, 0, videoShape[0], 0, 0, videoShape[0], videoShape[1]);

                        TensorImage modelInput = new TensorImage(DataType.FLOAT32);
                        modelInput.load(rgbBitmap);
                        modelInput = imageProcessor.process(modelInput);
                        try {
                            modelInputQueue.put(modelInput);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    } catch (InterruptedException e) {
                        Log.e("Error Exception", "MainActivity error: " + e.getMessage() + e.toString());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }).start();
    }

    public void inference() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        TensorImage modelInput = modelInputQueue.take();
                        TensorBuffer modelOutput = srTFLite.superResolution(modelInput);
                        try {
                            modelOutputQueue.put(modelOutput);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    } catch (InterruptedException e) {
                        Log.e("Error Exception", "MainActivity error: " + e.getMessage() + e.toString());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }).start();
    }

    public void afterProcess() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        TensorBuffer hwcOutputTensorBuffer = modelOutputQueue.take();
                        float[] hwcOutputData = hwcOutputTensorBuffer.getFloatArray();
                        int outHeight = outSize.getHeight();
                        int outWidth = outSize.getWidth();
                        int[] pixels = new int [outHeight * outWidth];
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
                        try {
                            viewOutQueue.put(pixels);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    } catch (InterruptedException e) {
                        Log.e("Error Exception", "MainActivity error: " + e.getMessage() + e.toString());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }).start();
    }

    public void viewProcess() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        long startTime = System.currentTimeMillis();
                        int[] outputPixels = viewOutQueue.take();
                        int outHeight = outSize.getHeight();
                        int outWidth = outSize.getWidth();
                        outputBitmap.setPixels(outputPixels, 0, outWidth, 0, 0, outWidth, outHeight);
                        Matrix matrix = new Matrix();
                        if (!isPICO) {
                            matrix.postRotate(90);
                        }
                        Bitmap postTransformImageBitmap = Bitmap.createBitmap(outputBitmap, 0, 0, outWidth, outHeight, matrix, false);
                        if (!isPICO) {
                            int tmp = outWidth;
                            outWidth = outHeight;
                            outHeight = tmp;
                        }
                        Canvas canvas = surfaceHolder.lockCanvas();
                        if (canvas != null) {
                            try{
                                canvas.drawBitmap(postTransformImageBitmap, null, new Rect(0, 0, outWidth, outHeight), null);
                            } finally {
                                surfaceHolder.unlockCanvasAndPost(canvas);
                            }
                        }
                        long endTime = System.currentTimeMillis();
                        updateTextView(Long.toString(endTime - startTime) + "ms");
                        Log.i("viewProcess:", Long.toString(endTime - startTime) + "ms");
                    } catch (InterruptedException e) {
                        Log.e("Error Exception", "MainActivity error: " + e.getMessage() + e.toString());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }).start();
    }
    public void ReceiveSRShow() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        long startTime = System.currentTimeMillis();
                        int [] rgbData = rgbBytesQueue.take();
                        inputBitmap.setPixels(rgbData, 0, videoShape[0], 0, 0, videoShape[0], videoShape[1]);
                        int[] outputSize = srTFLite.getOUTPUT_SIZE();
                        int outWidth = outputSize[2];
                        int outHeight = outputSize[1];

//                        int[] outPixels = srTFLite.superResolution(rgbBitmap);
                        srTFLite.superResolution(inputBitmap, outPixels);

                        outputBitmap.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight);
                        Matrix matrix = new Matrix();
                        if (!isPICO) {
                            matrix.postRotate(90);
                        }
                        Bitmap postTransformImageBitmap = Bitmap.createBitmap(outputBitmap, 0, 0, outWidth, outHeight, matrix, false);
                        if (!isPICO) {
                            int tmp = outWidth;
                            outWidth = outHeight;
                            outHeight = tmp;
                        }
                        Canvas canvas = surfaceHolder.lockCanvas();
                        if (canvas != null) {
                            try{
                                canvas.drawBitmap(postTransformImageBitmap, null, new Rect(0, 0, outWidth, outHeight), null);
                            } finally {
                                surfaceHolder.unlockCanvasAndPost(canvas);
                            }
                        }
                        long endTime = System.currentTimeMillis();
                        long costTime = endTime - startTime;
                        updateTextView(Long.toString(costTime) + "ms");
                    } catch (InterruptedException e) {
                        Log.e("Error Exception", "MainActivity error: " + e.getMessage() + e.toString());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }).start();
    }

    public void ReceiveSRShow2() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        long startTime = System.currentTimeMillis();
                        int [] rgbData = rgbBytesQueue.take();
                        Bitmap rgbBitmap = Bitmap.createBitmap(videoShape[0], videoShape[1], Bitmap.Config.ARGB_8888);
                        rgbBitmap.setPixels(rgbData, 0, videoShape[0], 0, 0, videoShape[0], videoShape[1]);
                        int[] outputSize = srTFLite.getOUTPUT_SIZE();
                        int outWidth = outputSize[2];
                        int outHeight = outputSize[1];


//                        int[] outPixels = srTFLite.superResolution(rgbBitmap);
                        srTFLite.superResolution(rgbBitmap, outPixels);

                        Bitmap outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
                        outBitmap.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight);

                        Matrix matrix = new Matrix();
                        if (!isPICO) {
                            matrix.postRotate(90);
                        }
                        Bitmap postTransformImageBitmap = Bitmap.createBitmap(outBitmap, 0, 0, outWidth, outHeight, matrix, false);

                        handler.post(()-> imageView.setImageBitmap(postTransformImageBitmap));
                        long endTime = System.currentTimeMillis();
                        long costTime = endTime - startTime;
                        updateTextView(Long.toString(costTime) + "ms");

                    } catch (InterruptedException e) {
                        Log.e("Error Exception", "MainActivity error: " + e.getMessage() + e.toString());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }).start();
    }


    public void updateTextView(String fps) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameSizeTextView = findViewById(R.id.frame_size);
                fpsTextView = findViewById(R.id.inference_time);
                fpsTextView.setText(fps);
            }
        });
    }
    public static void putData(int[] data) {
        try {
            Log.i("rgbQueue", "pushing data");
            rgbBytesQueue.put(data);
        } catch (InterruptedException e) {
            Log.i("rgbQueue", "pushing data error!");
            Thread.currentThread().interrupt();
        }
    }

    public native void mainDecoder(String url);
}