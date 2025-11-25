package com.example.teminai.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
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

import com.example.teminai.network.AbnormalWebSocketClient;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class CameraWebSocketMonitor {

    private static final String TAG = "CameraWSMonitor";

    public interface Listener {
        void onFrameSent(int frameCount);
        void onAnalysisResult(boolean isAbnormal, String label, double score);
        void onBufferProgress(int current, int required);
        void onError(String message, Throwable t);
    }

    private final Context context;
    private final CameraManager cameraManager;
    private final AbnormalWebSocketClient wsClient;
    private final Listener listener;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private boolean isMonitoring = false;
    private int frameCount = 0;
    private long lastCaptureTime = 0;
    // 안드로이드 부하 감소: 100ms → 500ms (초당 2프레임, 30초에 60프레임)
    private static final long CAPTURE_INTERVAL_MS = 500; // 500ms = 0.5초

    public CameraWebSocketMonitor(Context context, AbnormalWebSocketClient wsClient, Listener listener) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.wsClient = wsClient;
        this.listener = listener;
    }

    public void startMonitoring() {
        Log.d(TAG, "========== startMonitoring() CALLED ==========");

        if (isMonitoring) {
            Log.w(TAG, "⚠️ Already monitoring - ignoring request");
            return;
        }

        Log.d(TAG, "========== WEBSOCKET CAMERA MONITORING START ==========");
        isMonitoring = true;
        frameCount = 0;

        // WebSocket 연결
        Log.d(TAG, "Connecting WebSocket...");
        wsClient.connect();
        Log.d(TAG, "✅ WebSocket connect() called");

        // 백그라운드 스레드 시작
        Log.d(TAG, "Starting background thread...");
        startBackgroundThread();

        // 카메라 열기
        Log.d(TAG, "Opening camera...");
        openCamera();
    }

    public void stopMonitoring() {
        if (!isMonitoring) {
            Log.w(TAG, "Not monitoring");
            return;
        }

        Log.d(TAG, "========== WEBSOCKET CAMERA MONITORING STOP ==========");
        Log.d(TAG, "Total frames sent: " + frameCount);

        isMonitoring = false;
        closeCamera();
        stopBackgroundThread();

        // WebSocket 연결 종료
        wsClient.disconnect();
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
            Log.d(TAG, "========== CAMERA OPENING START ==========");

            String cameraId = getCameraId();
            if (cameraId == null) {
                Log.e(TAG, "❌ No camera found!");
                if (listener != null) listener.onError("카메라를 찾을 수 없습니다", null);
                return;
            }
            Log.d(TAG, "✅ Camera ID found: " + cameraId);

            // 권한 확인
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ Camera permission NOT granted!");
                if (listener != null) listener.onError("카메라 권한이 없습니다", null);
                return;
            }
            Log.d(TAG, "✅ Camera permission granted");

            // ImageReader 생성
            Log.d(TAG, "Creating ImageReader (480x360, JPEG, buffers=3)...");
            imageReader = ImageReader.newInstance(480, 360, ImageFormat.JPEG, 3);
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);
            Log.d(TAG, "✅ ImageReader created");

            // 카메라 열기
            Log.d(TAG, "Calling cameraManager.openCamera()...");
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
            Log.d(TAG, "✅ openCamera() called (waiting for callback...)");

        } catch (CameraAccessException e) {
            Log.e(TAG, "❌ CameraAccessException in openCamera()", e);
            Log.e(TAG, "Exception reason: " + e.getReason());
            Log.e(TAG, "Exception message: " + e.getMessage());
            if (listener != null) listener.onError("카메라 열기 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "❌ Unexpected exception in openCamera()", e);
            Log.e(TAG, "Exception type: " + e.getClass().getName());
            Log.e(TAG, "Exception message: " + e.getMessage());
            if (listener != null) listener.onError("예상치 못한 에러: " + e.getMessage(), e);
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
                return cameraIds[0];
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting camera ID", e);
        }
        return null;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "========== onOpened() CALLBACK ==========");
            cameraDevice = camera;
            Log.d(TAG, "✅ Camera opened successfully!");
            Log.d(TAG, "Camera device ID: " + camera.getId());
            createCaptureSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "========== onDisconnected() CALLBACK ==========");
            Log.w(TAG, "⚠️ Camera disconnected - ID: " + camera.getId());
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "========== onError() CALLBACK ==========");
            Log.e(TAG, "❌ Camera error code: " + error);
            Log.e(TAG, "Camera ID: " + camera.getId());

            String errorMsg;
            switch (error) {
                case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                    errorMsg = "ERROR_CAMERA_IN_USE (1) - 카메라가 다른 앱/프로세스에서 사용 중";
                    Log.e(TAG, "ERROR: " + errorMsg);
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    errorMsg = "ERROR_CAMERA_DEVICE (2) - 카메라 디바이스에 치명적 에러 발생";
                    Log.e(TAG, "ERROR: " + errorMsg);
                    Log.e(TAG, "HINT: 카메라 하드웨어 문제이거나 다른 프로세스가 독점 중일 수 있습니다");
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    errorMsg = "ERROR_CAMERA_SERVICE (3) - 카메라 서비스 치명적 에러";
                    Log.e(TAG, "ERROR: " + errorMsg);
                    Log.e(TAG, "HINT: 시스템 카메라 서비스에 문제가 있습니다");
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                    errorMsg = "ERROR_CAMERA_DISABLED (4) - 카메라가 정책에 의해 비활성화됨";
                    Log.e(TAG, "ERROR: " + errorMsg);
                    break;
                case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                    errorMsg = "ERROR_MAX_CAMERAS_IN_USE (5) - 최대 카메라 개수 도달";
                    Log.e(TAG, "ERROR: " + errorMsg);
                    break;
                default:
                    errorMsg = "UNKNOWN_ERROR (" + error + ")";
                    Log.e(TAG, "ERROR: " + errorMsg);
                    break;
            }

            camera.close();
            cameraDevice = null;

            if (listener != null) listener.onError("카메라 에러: " + errorMsg, null);
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
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
            );
            builder.addTarget(imageReader.getSurface());

            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            // JPEG 품질 낮춤 (100 → 70) - 파일 크기 감소, CPU/네트워크 부하 감소
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 70);

            captureSession.setRepeatingRequest(
                    builder.build(),
                    null,
                    backgroundHandler
            );

            Log.d(TAG, "✅ Started repeating capture (WebSocket mode: 480x360@70%, 500ms interval)");

        } catch (CameraAccessException e) {
            Log.e(TAG, "❌ Failed to start capture", e);
            if (listener != null) listener.onError("캡처 시작 실패", e);
        }
    }

    private final ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            if (!isMonitoring || !wsClient.isConnected()) {
                return;
            }

            // 간격 제어 (500ms마다 - 안드로이드 부하 감소)
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCaptureTime < CAPTURE_INTERVAL_MS) {
                return;
            }
            lastCaptureTime = currentTime;

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

                // WebSocket으로 전송
                wsClient.sendFrame(jpegBytes);

                if (listener != null && (frameCount % 10 == 0 || frameCount == 1)) {
                    listener.onFrameSent(frameCount);
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
