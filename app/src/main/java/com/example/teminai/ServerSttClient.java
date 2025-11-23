package com.example.teminai;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ServerSttClient {

    private static final String TAG = "ServerSttClient";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final WebView webView;
    private final String serverUrl;
    private final OkHttpClient httpClient = new OkHttpClient();

    private AudioRecord audioRecord;
    private boolean isRecording = false;

    public ServerSttClient(WebView webView, String serverUrl) {
        this.webView = webView;
        this.serverUrl = serverUrl;
    }

    public void startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording, skip");
            return;
        }

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
        );

        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid min buffer size: " + minBuffer);
            return;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBuffer * 2
            );
        } catch (SecurityException se) {
            Log.e(TAG, "No RECORD_AUDIO permission", se);
            sendErrorToJs("권한 없음: RECORD_AUDIO");
            return;
        }


        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            return;
        }

        isRecording = true;
        audioRecord.startRecording();

        Log.d(TAG, "Recording started");

        // 백그라운드에서 5초 정도 녹음 후 서버 전송 (필요하면 stopRecording으로 끊을 수 있게)
        new Thread(() -> captureAndSend(minBuffer * 2)).start();
    }

    public void stopRecording() {
        Log.d(TAG, "stopRecording called");
        isRecording = false;
    }

    @WorkerThread
    private void captureAndSend(int bufferSize) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferSize];

        long endTime = System.currentTimeMillis() + 5000; // 5초 녹음

        try {
            while (isRecording && System.currentTimeMillis() < endTime) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    baos.write(buffer, 0, read);
                }
            }
        } finally {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord.release();
            audioRecord = null;
            isRecording = false;
        }

        byte[] pcmData = baos.toByteArray();
        Log.d(TAG, "Captured PCM bytes: " + pcmData.length);

        sendToServer(pcmData);
    }

    private void sendToServer(byte[] pcmData) {
        // 서버에서 raw PCM 16kHz mono를 받는다고 가정 (원하면 WAV로 감싸도 됨)
        MediaType mediaType = MediaType.parse("audio/raw");
        RequestBody body = RequestBody.create(pcmData, mediaType);

        Request request = new Request.Builder()
                .url(serverUrl)   // 예: http://123.123.123.123:20330/stt
                .post(body)
                .build();

        Log.d(TAG, "Sending audio to server: " + serverUrl);

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "STT request failed", e);
                sendErrorToJs("STT 서버 요청 실패: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "STT server error: " + response.code());
                    sendErrorToJs("STT 서버 에러: " + response.code());
                    return;
                }

                // 서버에서 "텍스트만" 보내준다고 가정 (ex: plain/text)
                String text = response.body().string().trim();
                Log.d(TAG, "STT server response: " + text);
                sendResultToJs(text);
            }
        });
    }

    private void sendResultToJs(String text) {
        String safeText = text.replace("\"", "\\\"");
        String js = "window.receiveSpeech && window.receiveSpeech(\"" + safeText + "\");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void sendErrorToJs(String msg) {
        String safe = msg.replace("\"", "\\\"");
        String js = "console.log(\"[Server STT Error] " + safe + "\");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }
}