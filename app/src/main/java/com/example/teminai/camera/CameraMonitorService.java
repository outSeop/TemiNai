package com.example.teminai.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.example.teminai.ai.AbnormalAiClient;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class CameraMonitorService {

    private static final String TAG = "CameraMonitorService";

    public interface Listener {
        void onFrameSent(int frameCount);
        void onError(String message, Throwable t);
    }

    private final Context context;
    private final CameraManager cameraManager;
    private final AbnormalAiClient abnormalClient;
    private final Listener listener;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private boolean isMonitoring = false;
    private int frameCount = 0;
    private long lastCaptureTime = 0;
    private static final long CAPTURE_INTERVAL_MS = 1000; // 1초마다

    public CameraMonitorService(Context context, AbnormalAiClient abnormalClient, Listener listener) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.abnormalClient = abnormalClient;
        this.listener = listener;
    }

    public void startMonitoring() {
        if (isMonitoring) {
            Log.w(TAG, "Already monitoring");
            return;
        }

        Log.d(TAG, "========== CAMERA MONITORING START ==========");
        isMonitoring = true;
        frameCount = 0;

        startBackgroundThread();
        openCamera();
    }

    public void stopMonitoring() {
        if (!isMonitoring) {
            Log.w(TAG, "Not monitoring");
            return;
        }

        Log.d(TAG, "========== CAMERA MONITORING STOP ==========");
        Log.d(TAG, "Total frames sent: " + frameCount);

        isMonitoring = false;
        closeCamera();
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        Log.d(TAG, "✅ Background thread started");
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
                Log.d(TAG, "✅ Background thread stopped");
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    private void openCamera() {
        try {
            String cameraId = getCameraId();
            if (cameraId == null) {
                if (listener != null) listener.onError("카메라를 찾을 수 없습니다", null);
                return;
            }

            Log.d(TAG, "Opening camera: " + cameraId);

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ Camera permission not granted!");
                if (listener != null) listener.onError("카메라 권한이 없습니다", null);
                return;
            }

            // ImageReader 생성 (640x480 JPEG)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "❌ Failed to open camera", e);
            if (listener != null) listener.onError("카메라 열기 실패", e);
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        Log.d(TAG, "✅ Camera closed");
    }

    private String getCameraId() {
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length > 0) {
                return cameraIds[0]; // 첫 번째 카메라 사용
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting camera ID", e);
        }
        return null;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            Log.d(TAG, "✅ Camera opened successfully");
            createCaptureSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            Log.w(TAG, "⚠️ Camera disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            Log.e(TAG, "❌ Camera error: " + error);
            if (listener != null) listener.onError("카메라 에러: " + error, null);
        }
    };

    private void createCaptureSession() {
        try {
            Surface surface = imageReader.getSurface();

            cameraDevice.createCaptureSession(
                    Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            Log.d(TAG, "✅ Capture session configured");
                            startRepeatingCapture();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "❌ Capture session configuration failed");
                            if (listener != null)
                                listener.onError("캡처 세션 설정 실패", null);
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "❌ Failed to create capture session", e);
            if (listener != null) listener.onError("캡처 세션 생성 실패", e);
        }
    }

    private void startRepeatingCapture() {
        try {
            // TEMPLATE_PREVIEW로 변경 (연속 캡처에 적합)
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
            );
            builder.addTarget(imageReader.getSurface());

            // Auto-focus 설정
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // Auto-exposure 설정
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            captureSession.setRepeatingRequest(
                    builder.build(),
                    null,
                    backgroundHandler
            );

            Log.d(TAG, "✅ Started repeating capture (interval: " + CAPTURE_INTERVAL_MS + "ms)");

        } catch (CameraAccessException e) {
            Log.e(TAG, "❌ Failed to start capture", e);
            if (listener != null) listener.onError("캡처 시작 실패", e);
        }
    }

    private final ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            if (!isMonitoring) {
                Log.d(TAG, "⏸️ Not monitoring, skipping frame");
                return;
            }

            // 주기적으로만 전송 (1초마다)
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCaptureTime < CAPTURE_INTERVAL_MS) {
                return;
            }
            lastCaptureTime = currentTime;

            Log.d(TAG, "🎬 onImageAvailable called, attempting to capture frame...");

            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) {
                    return;
                }

                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] jpegBytes = new byte[buffer.remaining()];
                buffer.get(jpegBytes);

                frameCount++;
                Log.d(TAG, "📸 Frame #" + frameCount + " captured, size: " + jpegBytes.length + " bytes");

                // 서버로 전송
                if (abnormalClient != null) {
                    abnormalClient.sendFrame(jpegBytes);
                    if (listener != null) {
                        listener.onFrameSent(frameCount);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "❌ Error processing image", e);
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };
}
