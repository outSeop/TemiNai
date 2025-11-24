package com.example.teminai.network;

import android.util.Log;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class AbnormalWebSocketClient extends WebSocketListener {

    private static final String TAG = "AbnormalWebSocketClient";

    public interface Listener {
        void onAnalysisResult(boolean isAbnormal, String label, double score);
        void onBufferStatus(int bufferSize, int requiredSize, boolean bufferReady);
        void onError(String message, Throwable t);
        void onConnected();
        void onDisconnected();
    }

    private final OkHttpClient httpClient;
    private final String wsUrl;
    private final Listener listener;

    private WebSocket webSocket;
    private boolean isConnected = false;

    public AbnormalWebSocketClient(OkHttpClient httpClient, String baseUrl, Listener listener) {
        this.httpClient = httpClient;
        // HTTP URL을 WebSocket URL로 변환
        this.wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
                     + "/abnormal/stream";
        this.listener = listener;
    }

    public void connect() {
        if (isConnected) {
            Log.w(TAG, "Already connected");
            return;
        }

        Log.d(TAG, "========== WEBSOCKET CONNECTING ==========");
        Log.d(TAG, "   URL: " + wsUrl);

        Request request = new Request.Builder()
                .url(wsUrl)
                .build();

        webSocket = httpClient.newWebSocket(request, this);
    }

    public void disconnect() {
        if (webSocket != null) {
            Log.d(TAG, "========== WEBSOCKET DISCONNECTING ==========");
            webSocket.close(1000, "Client closing");
            webSocket = null;
            isConnected = false;
        }
    }

    public void sendFrame(byte[] jpegBytes) {
        if (!isConnected || webSocket == null) {
            Log.e(TAG, "❌ Cannot send frame: WebSocket not connected");
            return;
        }

        Log.d(TAG, "📤 Sending frame via WebSocket: " + jpegBytes.length + " bytes");
        webSocket.send(ByteString.of(jpegBytes));
    }

    // ========== WebSocketListener 구현 ==========

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        isConnected = true;
        Log.d(TAG, "✅ WebSocket connected!");
        Log.d(TAG, "   Protocol: " + response.protocol());

        if (listener != null) {
            listener.onConnected();
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        Log.d(TAG, "📥 WebSocket message received (text): " + text);

        try {
            JSONObject json = new JSONObject(text);

            boolean success = json.optBoolean("success", false);
            if (!success) {
                Log.e(TAG, "❌ Server returned success=false");
                if (listener != null) listener.onError("Server error: success=false", null);
                return;
            }

            // 버퍼 상태
            boolean bufferReady = json.optBoolean("buffer_ready", false);
            int bufferSize = json.optInt("buffer_size", 0);
            int requiredSize = json.optInt("required_size", 60);

            Log.d(TAG, "📊 Buffer status: " + bufferSize + "/" + requiredSize +
                  " (ready: " + bufferReady + ")");

            if (listener != null) {
                listener.onBufferStatus(bufferSize, requiredSize, bufferReady);
            }

            // 분석 결과 (result가 있을 때만)
            if (json.has("result") && !json.isNull("result")) {
                JSONObject result = json.getJSONObject("result");

                boolean isAbnormal = result.optBoolean("isAbnormal", false);
                String label = result.optString("label", "");
                double score = result.optDouble("score", 0.0);

                Log.d(TAG, "✅ ANALYSIS RESULT:");
                Log.d(TAG, "   isAbnormal: " + isAbnormal);
                Log.d(TAG, "   label: " + label);
                Log.d(TAG, "   score: " + score);

                if (listener != null) {
                    listener.onAnalysisResult(isAbnormal, label, score);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error parsing WebSocket message", e);
            if (listener != null) listener.onError("JSON parsing error", e);
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        Log.d(TAG, "📥 WebSocket message received (binary): " + bytes.size() + " bytes");
        // 바이너리 메시지는 현재 사용하지 않음
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        Log.d(TAG, "⚠️ WebSocket closing: code=" + code + ", reason=" + reason);
        webSocket.close(1000, null);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        isConnected = false;
        Log.d(TAG, "❌ WebSocket closed: code=" + code + ", reason=" + reason);

        if (listener != null) {
            listener.onDisconnected();
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        isConnected = false;
        Log.e(TAG, "❌ WebSocket failure", t);

        if (response != null) {
            Log.e(TAG, "   Response code: " + response.code());
            Log.e(TAG, "   Response message: " + response.message());
        }

        if (listener != null) {
            listener.onError("WebSocket connection failed: " + t.getMessage(), t);
        }
    }

    public boolean isConnected() {
        return isConnected;
    }
}
