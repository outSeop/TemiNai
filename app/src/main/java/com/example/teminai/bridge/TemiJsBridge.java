package com.example.teminai.bridge;

import android.app.Activity;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.example.teminai.camera.CameraMonitorService;
import com.example.teminai.camera.CameraWebSocketMonitor;
import com.example.teminai.stt.SttRecorderClient;
import com.example.teminai.ai.AbnormalLastClient;
import com.example.teminai.ai.AbnormalAiClient;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

import java.util.Map;

public class TemiJsBridge {

    private static final String TAG = "TemiJsBridge";

    private final Activity activity;
    private final Robot robot;
    private final WebView webView;
    private final SttRecorderClient sttClient;
    private final Map<String, String> map;
    private final AbnormalLastClient abnormalLastClient;
    private final AbnormalAiClient abnormalAiClient;
    private final CameraMonitorService cameraMonitor;
    private final CameraWebSocketMonitor wsMonitor;

    public TemiJsBridge(
            Activity activity,
            Robot robot,
            WebView webView,
            SttRecorderClient sttClient,
            AbnormalAiClient abnormalAiClient,
            AbnormalLastClient abnormalLastClient,
            CameraMonitorService cameraMonitor,
            CameraWebSocketMonitor wsMonitor,
            Map<String, String> map
    ) {
        this.activity = activity;
        this.robot = robot;
        this.webView = webView;
        this.sttClient = sttClient;
        this.abnormalAiClient = abnormalAiClient;
        this.abnormalLastClient = abnormalLastClient;
        this.cameraMonitor = cameraMonitor;
        this.wsMonitor = wsMonitor;
        this.map = map;
    }

    @JavascriptInterface
    public void speak(String text) {
        Log.d(TAG, "Temi.speak called from JS: " + text);
        if (robot != null) {
            activity.runOnUiThread(() ->
                    robot.speak(TtsRequest.create(text, false))
            );
        }
    }

    @JavascriptInterface
    public void goToBooth(String locationID) {
        try {
            String target = convertLocationID(locationID);
            if (target == null || target.isEmpty()) {
                Log.e(TAG, "goToBooth: Invalid location ID: " + locationID);
                sendJsError("위치 ID가 잘못되었습니다: " + locationID);
                return;
            }

            goTo(target);
        } catch (Exception e) {
            Log.e(TAG, "goToBooth Exception", e);
            sendJsError("알 수 없는 오류가 발생했습니다.");
        }
    }

    private String convertLocationID(String locationID) {
        if (locationID == null) return null;
        if (map != null && map.containsKey(locationID)) {
            return map.get(locationID);
        }
        return null;
    }

    @JavascriptInterface
    public void goTo(String location) {
        Log.d(TAG, "Temi.goTo called from JS: " + location);
        if (robot != null) {
            activity.runOnUiThread(() -> robot.goTo(location));
        }
        Log.d(TAG, "Finished goTo: " + location);
    }

    @JavascriptInterface
    public void startListening() {
        Log.d(TAG, "Start listening called from JS");

        if (sttClient == null) {
            Log.e(TAG, "sttClient is null, cannot start STT");
            return;
        }
        sttClient.startRecording();
    }

    @JavascriptInterface
    public void stopListening() {
        Log.d(TAG, "Stop listening by user input");
        if (sttClient != null) {
            sttClient.stopRecording();
        }
    }
    // ========== WebSocket 스트리밍 방식 (NEW - 빠름!) ==========

    @JavascriptInterface
    public void startWebSocketMonitoring() {
        Log.d(TAG, "🚀 START WEBSOCKET MONITORING (React → Android)");
        if (wsMonitor != null) {
            wsMonitor.startMonitoring();
        } else {
            Log.e(TAG, "❌ wsMonitor is null!");
            sendJsError("WebSocket 모니터 서비스가 초기화되지 않았습니다.");
        }
    }

    @JavascriptInterface
    public void stopWebSocketMonitoring() {
        Log.d(TAG, "🛑 STOP WEBSOCKET MONITORING (React → Android)");
        if (wsMonitor != null) {
            wsMonitor.stopMonitoring();
        } else {
            Log.e(TAG, "❌ wsMonitor is null!");
        }
    }

    // ========== HTTP 방식 (기존 - 백업용) ==========

    @JavascriptInterface
    public void startCameraMonitoring() {
        Log.d(TAG, "🎥 START CAMERA MONITORING HTTP (React → Android)");
        if (cameraMonitor != null) {
            cameraMonitor.startMonitoring();
        } else {
            Log.e(TAG, "❌ cameraMonitor is null!");
            sendJsError("카메라 모니터 서비스가 초기화되지 않았습니다.");
        }
    }

    @JavascriptInterface
    public void stopCameraMonitoring() {
        Log.d(TAG, "🛑 STOP CAMERA MONITORING HTTP (React → Android)");
        if (cameraMonitor != null) {
            cameraMonitor.stopMonitoring();
        } else {
            Log.e(TAG, "❌ cameraMonitor is null!");
        }
    }

    // ========== 서버 polling 방식 (기존 방식) ==========

    @JavascriptInterface
    public void startAbnormalMonitor() {
        Log.d(TAG, "Starting abnormal monitor (polling)");
        if (abnormalLastClient != null) {
            abnormalLastClient.start();
        } else {
            Log.e(TAG, "abnormalLastClient is null");
        }
    }

    @JavascriptInterface
    public void stopAbnormalMonitor() {
        Log.d(TAG, "Stopping abnormal monitor (polling)");
        if (abnormalLastClient != null) {
            abnormalLastClient.stop();
        } else {
            Log.e(TAG, "abnormalLastClient is null");
        }
    }

    @JavascriptInterface
    public void sendAbnormalFrame(String base64Jpeg) {
        Log.d(TAG, "========== ABNORMAL FRAME SEND START ==========");
        Log.d(TAG, "Received base64 string length: " + (base64Jpeg != null ? base64Jpeg.length() : 0));

        if (base64Jpeg == null || base64Jpeg.isEmpty()) {
            Log.e(TAG, "❌ Base64 string is null or empty!");
            sendJsError("전송할 이미지가 없습니다.");
            return;
        }

        if (abnormalAiClient == null) {
            Log.e(TAG, "❌ abnormalAiClient is null - client not initialized!");
            sendJsError("이상행동 감지 클라이언트가 초기화되지 않았습니다.");
            return;
        }

        try {
            Log.d(TAG, "Decoding base64 string to byte array...");
            byte[] frame = Base64.decode(base64Jpeg, Base64.DEFAULT);
            Log.d(TAG, "✅ Decoded successfully, frame size: " + frame.length + " bytes");

            Log.d(TAG, "Sending frame to AbnormalAiClient...");
            abnormalAiClient.sendFrame(frame);
            Log.d(TAG, "✅ Frame sent to client");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "❌ Base64 decode error - invalid base64 string", e);
            sendJsError("이미지 디코딩 실패: 잘못된 Base64 형식");
        } catch (Exception e) {
            Log.e(TAG, "❌ Unexpected error in sendAbnormalFrame", e);
            sendJsError("이미지 디코딩 실패: " + e.getMessage());
        }
        Log.d(TAG, "========== ABNORMAL FRAME SEND END ==========");
    }

    private void sendJsError(String message) {
        String safe = message == null ? "" : message.replace("\"", "\\\"");
        String js = "window.onTemiError && window.onTemiError(\"" + safe + "\");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }
}