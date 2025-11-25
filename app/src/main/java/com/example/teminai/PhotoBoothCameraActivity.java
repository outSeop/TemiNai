// app/src/main/java/com/example/teminai/PhotoBoothCameraActivity.java
package com.example.teminai;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PhotoBoothCameraActivity extends Activity
        implements SurfaceHolder.Callback, Camera.PictureCallback {

    private static final String TAG = "PhotoBoothCamera";

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private Camera camera;

    private static final int MAX_PHOTOS = 4;
    private int capturedCount = 0;
    private final List<String> capturedBase64List = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 화면 꺼지지 않게
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 미리 만들어 둔 레이아웃 (전체 화면 SurfaceView 하나)
        setContentView(R.layout.activity_photo_booth_camera);

        surfaceView = findViewById(R.id.camera_preview);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
    }

    // SurfaceHolder.Callback
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCameraAndStartPreview(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 별도 처리 X (필요하면 여기서 프리뷰 리스타트 가능)
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void openCameraAndStartPreview(SurfaceHolder holder) {
        try {
            // Temi는 보통 front camera = 1
            camera = Camera.open(1);
        } catch (Exception e) {
            Log.e(TAG, "카메라 오픈 실패", e);
            finishSafely(false);
            return;
        }

        try {
            Camera.Parameters params = camera.getParameters();

            // ▼ 프리뷰 사이즈(안전하게 640x480 있으면 그걸 선택)
            Camera.Size bestPreview = null;
            List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
            if (previewSizes != null) {
                for (Camera.Size s : previewSizes) {
                    if (s.width == 640 && s.height == 480) {
                        bestPreview = s;
                        break;
                    }
                }
                if (bestPreview == null && !previewSizes.isEmpty()) {
                    bestPreview = previewSizes.get(0);
                }
            }
            if (bestPreview != null) {
                params.setPreviewSize(bestPreview.width, bestPreview.height);
            }

            // ▼ 포커스 모드: continuous-picture 지원 안 하면 건드리지 않기
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    Log.d(TAG, "focus mode = CONTINUOUS_PICTURE");
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    Log.d(TAG, "focus mode = AUTO");
                } else {
                    // Temi 로그 상으로는 fixed 만 지원
                    Log.d(TAG, "focus mode 변경하지 않음 (fixed 등)");
                }
            }

            // 🔴 여기서 예외 터지고 있었음 → try / catch 로 감쌈
            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                Log.w(TAG, "setParameters 실패, 기본 파라미터로 진행", e);
                // 실패해도 그냥 기본 파라미터로 계속 진행
            }

            camera.setDisplayOrientation(90); // 필요에 따라 조정
            camera.setPreviewDisplay(holder);
            camera.startPreview();

            // 프리뷰가 뜨면 바로 촬영 시퀀스 시작
            startCaptureSequence();

        } catch (IOException e) {
            Log.e(TAG, "프리뷰 시작 실패", e);
            finishSafely(false);
        }
    }

    private void startCaptureSequence() {
        // 간단히: 바로 첫 장 찍고, 이후 1.5초 간격으로 자동 촬영
        capturedCount = 0;
        capturedBase64List.clear();
        takeNextPicture();
    }

    private void takeNextPicture() {
        if (camera == null) {
            finishSafely(false);
            return;
        }

        if (capturedCount >= MAX_PHOTOS) {
            // 다 찍었으면 결과 리턴
            finishSafely(true);
            return;
        }

        try {
            camera.takePicture(null, null, this);
        } catch (RuntimeException e) {
            Log.e(TAG, "takePicture 실패", e);
            finishSafely(false);
        }
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {
        if (data != null) {
            try {
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                    String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                    String dataUrl = "data:image/jpeg;base64," + base64;
                    capturedBase64List.add(dataUrl);
                    capturedCount++;
                }
            } catch (Exception e) {
                Log.e(TAG, "사진 처리 중 오류", e);
            }
        }

        // 다음 장 촬영 준비
        try {
            camera.startPreview();
        } catch (RuntimeException e) {
            Log.e(TAG, "startPreview 실패", e);
            finishSafely(false);
            return;
        }

        // 약간의 텀 후 다음 장 촬영
        surfaceView.postDelayed(new Runnable() {
            @Override
            public void run() {
                takeNextPicture();
            }
        }, 1500); // 1.5초 간격 (원하면 조정)
    }

    private void finishSafely(boolean success) {
        if (success) {
            try {
                JSONArray arr = new JSONArray();
                for (String base64 : capturedBase64List) {
                    arr.put(base64);
                }
                getIntent().putExtra("photosJson", arr.toString());
                setResult(RESULT_OK, getIntent());
            } catch (Exception e) {
                Log.e(TAG, "결과 JSON 생성 실패", e);
                setResult(RESULT_CANCELED);
            }
        } else {
            setResult(RESULT_CANCELED);
        }

        releaseCamera();
        finish();
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignore) {
            }
            try {
                camera.release();
            } catch (Exception ignore) {
            }
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }
}