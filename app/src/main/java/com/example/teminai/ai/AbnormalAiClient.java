package com.example.teminai.ai;

import android.util.Log;

import com.example.teminai.network.TemiServerClient;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AbnormalAiClient {

    private static final String TAG = "AbnormalAiClient";

    public interface Listener {
        void onResult(boolean isAbnormal, String label, double score);
        void onError(String message, Throwable t);
    }

    private final TemiServerClient serverClient;
    private final String apiPath;
    private final Listener listener;

    public AbnormalAiClient(TemiServerClient serverClient, String apiPath, Listener listener) {
        this.serverClient = serverClient;
        this.apiPath = apiPath;
        this.listener = listener;
    }

    /** JPEG 프레임 전송 */
    public void sendFrame(byte[] jpegBytes) {
        Log.d(TAG, "---------- sendFrame START ----------");

        if (jpegBytes == null || jpegBytes.length == 0) {
            Log.e(TAG, "❌ jpegBytes is null or empty!");
            if (listener != null) listener.onError("전송할 이미지가 없습니다.", null);
            return;
        }

        String url = serverClient.buildUrl(apiPath + "/frame");
        Log.d(TAG, "📤 Sending frame to server");
        Log.d(TAG, "   URL: " + url);
        Log.d(TAG, "   Frame size: " + jpegBytes.length + " bytes (" + (jpegBytes.length / 1024) + " KB)");

        // Multipart form-data로 전송 (서버가 file 필드 기대)
        RequestBody fileBody = RequestBody.create(
                jpegBytes,
                MediaType.parse("image/jpeg")
        );

        RequestBody requestBody = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", "frame.jpg", fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        Log.d(TAG, "🚀 HTTP POST request initiated...");

        serverClient.getHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "❌ HTTP REQUEST FAILED!", e);
                Log.e(TAG, "   Error message: " + e.getMessage());
                Log.e(TAG, "   Error class: " + e.getClass().getName());
                if (listener != null) listener.onError("이상행동 서버 요청 실패: " + e.getMessage(), e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                String message = response.message();

                Log.d(TAG, "📥 HTTP RESPONSE RECEIVED");
                Log.d(TAG, "   Status code: " + code);
                Log.d(TAG, "   Status message: " + message);

                String json = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "📄 Response body: " + json);

                if (!response.isSuccessful()) {
                    Log.e(TAG, "❌ Server returned error code: " + code);
                    Log.e(TAG, "   Error response body: " + json);
                    if (listener != null) listener.onError("서버 오류 " + code + ": " + json, null);
                    return;
                }

                try {
                    JSONObject root = new JSONObject(json);

                    // /abnormal/frame 구조에 맞게 result 안에서 꺼낸다
                    boolean success = root.optBoolean("success", false);
                    Log.d(TAG, "   Response success: " + success);

                    if (!success) {
                        Log.e(TAG, "❌ Server returned success=false");
                        if (listener != null) listener.onError("success=false", null);
                        return;
                    }

                    // 버퍼링 정보 로깅
                    boolean bufferReady = root.optBoolean("buffer_ready", false);
                    int bufferSize = root.optInt("buffer_size", 0);
                    int requiredSize = root.optInt("required_size", 0);

                    Log.d(TAG, "📊 Buffer status:");
                    Log.d(TAG, "   buffer_ready: " + bufferReady);
                    Log.d(TAG, "   buffer_size: " + bufferSize + "/" + requiredSize);

                    JSONObject result = root.optJSONObject("result");
                    if (result == null) {
                        // 버퍼링 중일 때는 정상 상태 (에러 아님)
                        Log.d(TAG, "⏳ Buffering... (" + bufferSize + "/" + requiredSize + " frames)");
                        return;
                    }

                    boolean isAbnormal = result.optBoolean("isAbnormal", false);
                    String label = result.optString("label", "");
                    double score = result.optDouble("score", 0.0);

                    Log.d(TAG, "✅ ANALYSIS RESULT:");
                    Log.d(TAG, "   isAbnormal: " + isAbnormal);
                    Log.d(TAG, "   label: " + label);
                    Log.d(TAG, "   score: " + score);

                    if (listener != null) {
                        listener.onResult(isAbnormal, label, score);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "❌ JSON parsing error", e);
                    Log.e(TAG, "   Raw JSON: " + json);
                    if (listener != null) listener.onError("JSON 파싱 오류", e);
                }

                Log.d(TAG, "---------- sendFrame END ----------");
            }
        });
    }
}