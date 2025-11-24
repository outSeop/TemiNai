package com.example.teminai.ai;

import android.util.Log;

import com.example.teminai.network.TemiServerClient;

import org.json.JSONObject;

import java.io.IOException;

import android.os.Handler;
import android.os.Looper;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class AbnormalLastClient {

    private static final String TAG = "AbnormalLastClient";

    public interface Listener {
        void onResult(boolean isAbnormal,
                      String label,
                      double score,
                      String time,
                      String camera);
        void onError(String message, Throwable t);
    }

    private final TemiServerClient serverClient;
    private final String apiPath;   // 예: "/abnormal/last"
    private final Listener listener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long intervalMs = 3000L;

    private boolean isPolling = false;
    private String lastKey = null;  // 중복 이벤트 방지용

    public AbnormalLastClient(TemiServerClient serverClient, String apiPath, Listener listener) {
        this.serverClient = serverClient;
        this.apiPath = apiPath;
        this.listener = listener;
    }

    public void start() {
        if (isPolling) return;
        isPolling = true;
        handler.post(pollRunnable);
    }

    public void stop() {
        isPolling = false;
        handler.removeCallbacks(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling) return;
            pollOnce();
            handler.postDelayed(this, intervalMs);
        }
    };

    private void pollOnce() {
        String url = serverClient.buildUrl(apiPath);
        Log.d(TAG, "Polling abnormal last: " + url);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        serverClient.getHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "abnormal/last request failed", e);
                if (listener != null) {
                    listener.onError("이상행동 마지막 결과 요청 실패: " + e.getMessage(), e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String msg = "abnormal/last 응답 오류: " + response.code();
                    Log.e(TAG, msg);
                    if (listener != null) listener.onError(msg, null);
                    return;
                }

                String json = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "abnormal/last raw response: " + json);

                try {
                    JSONObject root = new JSONObject(json);
                    boolean exists = root.optBoolean("exists", false);
                    if (!exists || !root.has("result")) {
                        // 아직 예측 결과 없음
                        return;
                    }

                    JSONObject result = root.getJSONObject("result");

                    boolean isAbnormal = result.optBoolean("isAbnormal", false);
                    String label = result.optString("label",
                            isAbnormal ? "이상 행동" : "정상 행동");
                    double score = result.optDouble("score", 0.0);
                    String time = result.optString("time", "");
                    String camera = result.optString("camera", "테미 카메라");

                    String key = time + "|" + label + "|" + isAbnormal;
                    if (key.equals(lastKey)) {
                        // 같은 이벤트라면 무시
                        return;
                    }
                    lastKey = key;

                    if (listener != null) {
                        listener.onResult(isAbnormal, label, score, time, camera);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "abnormal/last json parse error", e);
                    if (listener != null) listener.onError("JSON 파싱 오류", e);
                }
            }
        });
    }
}