package com.example.ffmpegvideoplayer;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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

public class MainActivity extends AppCompatActivity {

    private static final int QUEUE_CAPACITY = 4096;
    private static BlockingQueue<int[]> rgbBytesQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    // width, height
    private static int[] videoShape = new int[] {960, 540};

    static {
        System.loadLibrary("ffmpegvideoplayer");
    }

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private TextView frameSizeTextView;
    private TextView fpsTextView;
    private boolean isPICO = false;
    private InferenceTFLite srTFLite;
    private int[] outPixels;
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

        initModel();

        // Decoding Threads
        new Thread(new Runnable() {
            @Override
            public void run() {
                mainDecoder(getString(R.string.video_url));
            }
        }).start();


        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
//               ReceiveAndShow();
                ReceiveSRShow();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });

//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                while (true) {
//                    try {
//                        int[] currentData = rgbBytesQueue.take();
//                        String message = arrayToString(currentData);
//                        updateTextView(message);
//                    } catch (InterruptedException e) {
//                        Log.e("Error Exception", "MainActivity error: " + e.getMessage() + e.toString());
//                        Thread.currentThread().interrupt();
//                        break;
//                    }
//                }
//            }
//        }).start();
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

    public void ReceiveSRShow() {
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

//                        Runtime runtime = Runtime.getRuntime();
//                        long freeMem = runtime.freeMemory();
//                        Log.i("Memory left", freeMem + "bytes");
//                        int[] outPixels = srTFLite.superResolution(rgbBitmap);

                        Bitmap outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
//                        outBitmap.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight);
                        outBitmap.setPixels(rgbData, 0, outWidth, 0, 0, outWidth, outHeight);

                        Matrix matrix = new Matrix();
                        if (!isPICO) {
                            matrix.postRotate(90);
                        }
                        Bitmap postTransformImageBitmap = Bitmap.createBitmap(outBitmap, 0, 0, outWidth, outHeight, matrix, false);
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
//                        rgbBitmap.recycle();
//                        rgbBitmap = null;
//                        outBitmap.recycle();
//                        outBitmap = null;
//                        postTransformImageBitmap.recycle();
//                        postTransformImageBitmap = null;
//                        System.gc();
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
            rgbBytesQueue.put(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public native void mainDecoder(String url);
}