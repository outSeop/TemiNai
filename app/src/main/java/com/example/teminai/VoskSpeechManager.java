package com.example.teminai;

import android.content.Context;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;

public class VoskSpeechManager {

    private static final String TAG = "VoskSpeechManager";

    private Model model;
    private SpeechService speechService;

    private final Context context;
    private final SpeechCallback callback;

    // 결과 전달 콜백
    public interface SpeechCallback {
        void onResult(String text);
        void onError(String error);
    }

    public VoskSpeechManager(Context context, SpeechCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /** 모델 로드 */
    public void loadModel() {
        String modelPath = "/sdcard/temi_assets/vosk/vosk-model-small-ko-0.22";
        try {
            model = new Model(modelPath);
            Log.d(TAG, "Vosk model loaded from sdcard");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load Vosk model", e);
        }
    }
    /** STT 시작 */
    public void startListening() {
        if (model == null) {
            callback.onError("모델이 아직 로드되지 않았습니다.");
            return;
        }

        try {
            Recognizer recognizer = new Recognizer(model, 16000.0f);

            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(new org.vosk.android.RecognitionListener() {

                @Override
                public void onPartialResult(String hypothesis) {
                    Log.d(TAG, "Partial: " + hypothesis);
                }

                @Override
                public void onResult(String hypothesis) {
                    Log.d(TAG, "Result: " + hypothesis);
                    callback.onResult(hypothesis); // ★ 수정된 부분
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    Log.d(TAG, "Final: " + hypothesis);
                    callback.onResult(hypothesis); // ★ 필요하면 여기서도 전달
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Vosk Error", e);
                    callback.onError("STT 오류: " + e.getMessage()); // ★ 수정된 부분
                }

                @Override
                public void onTimeout() {
                    Log.d(TAG, "Timeout");
                    callback.onError("STT 타임아웃");
                }
            });

        } catch (IOException e) {
            Log.e(TAG, "STT start failed", e);
            callback.onError("STT 시작 실패: " + e.getMessage());
        }
    }

    /** 종료 */
    public void stop() {
        try {
            if (speechService != null) {
                speechService.stop();
                speechService = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop error", e);
        }
    }
}