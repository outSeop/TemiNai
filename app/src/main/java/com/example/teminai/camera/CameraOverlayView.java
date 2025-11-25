package com.example.teminai.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;

import java.io.IOException;
import java.util.List;

public class CameraOverlayView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "CameraOverlayView";
    private Camera camera;

    public CameraOverlayView(Context ctx) {
        super(ctx);
        init();
    }

    public CameraOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        setSurfaceTextureListener(this);
        // TextureView 자체를 90도 회전 (카메라가 180도 뒤집힌 상태 보정)
        setRotation(90f);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        openCamera(surface);
    }

    private void openCamera(SurfaceTexture surfaceTexture) {
        try {
            camera = Camera.open(1); // front camera
        } catch (Exception e) {
            Log.e(TAG, "카메라 오픈 실패", e);
            return;
        }

        try {
            Camera.Parameters params = camera.getParameters();

            // 미리보기 사이즈 최적화 (640x480 기준)
            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            Camera.Size best = null;
            for (Camera.Size s : sizes) {
                if (s.width == 640 && s.height == 480) {
                    best = s;
                    break;
                }
            }
            if (best != null) params.setPreviewSize(best.width, best.height);

            // 포커스 모드 안전 처리
            List<String> focus = params.getSupportedFocusModes();
            if (focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                Log.w(TAG, "setParameters 실패", e);
            }

            camera.setDisplayOrientation(90);
            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();

        } catch (IOException e) {
            Log.e(TAG, "preview 시작 실패", e);
        }
    }

    public void capture(Camera.PictureCallback callback) {
        if (camera != null) {
            camera.takePicture(null, null, callback);
        }
    }

    public void stop() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignore) {}
            try { camera.release(); } catch (Exception ignore) {}
            camera = null;
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stop();
        return true;
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
}