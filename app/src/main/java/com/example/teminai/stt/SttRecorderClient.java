package com.example.teminai.stt;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.example.teminai.network.TemiServerClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SttRecorderClient {

    private static final String TAG = "SttRecorderClient";

    public interface Listener {
        void onResult(String text);
        void onError(String message, Throwable t);
    }

    private final TemiServerClient serverClient;
    private final String sttPath; // 예: "/stt"
    private final Listener listener;

    // 녹음 관련
    private final int sampleRate = 16000;
    private final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private ByteArrayOutputStream pcmBuffer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public SttRecorderClient(TemiServerClient serverClient, String sttPath, Listener listener) {
        this.serverClient = serverClient;
        this.sttPath = sttPath;
        this.listener = listener;
    }

    public synchronized void startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return;
        }

        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBufferSize <= 0) {
            if (listener != null) listener.onError("AudioRecord buffer size error", null);
            return;
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            if (listener != null) listener.onError("AudioRecord init failed", null);
            return;
        }

        pcmBuffer = new ByteArrayOutputStream();
        isRecording = true;

        audioRecord.startRecording();
        Log.d(TAG, "Recording started");

        executor.execute(() -> {
            byte[] buffer = new byte[minBufferSize];
            try {
                while (isRecording && !Thread.currentThread().isInterrupted()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        pcmBuffer.write(buffer, 0, read);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Recording loop error", e);
            } finally {
                Log.d(TAG, "Recording loop finished");
            }
        });
    }

    public synchronized void stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "stopRecording called but not recording");
            return;
        }

        isRecording = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.w(TAG, "audioRecord.stop() error", e);
            }
            audioRecord.release();
            audioRecord = null;
        }

        byte[] pcmBytes = pcmBuffer != null ? pcmBuffer.toByteArray() : null;
        pcmBuffer = null;

        if (pcmBytes == null || pcmBytes.length == 0) {
            if (listener != null) listener.onError("녹음된 음성이 없습니다.", null);
            return;
        }

        Log.d(TAG, "Captured PCM bytes: " + pcmBytes.length);

        // PCM → WAV 변환
        byte[] wavBytes = pcmToWav(pcmBytes, sampleRate, 1, 16);

        // 서버로 전송
        sendToServer(wavBytes);
    }

    private void sendToServer(byte[] wavBytes) {
        String url = serverClient.buildUrl(sttPath);
        Log.d(TAG, "Sending audio to server: " + url + " (" + wavBytes.length + " bytes)");

        RequestBody fileBody = RequestBody.create(
                wavBytes,
                MediaType.parse("audio/wav")
        );

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        serverClient.getHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "❌ STT request failed", e);

                String errorMsg;
                if (e instanceof java.net.SocketTimeoutException) {
                    errorMsg = "STT 서버 응답 시간 초과. 네트워크를 확인해주세요.";
                } else if (e instanceof java.net.UnknownHostException) {
                    errorMsg = "STT 서버에 연결할 수 없습니다. 서버 주소를 확인해주세요.";
                } else if (e instanceof java.net.ConnectException) {
                    errorMsg = "STT 서버가 응답하지 않습니다. 서버가 실행 중인지 확인해주세요.";
                } else {
                    errorMsg = "STT 서버 요청 실패: " + e.getMessage();
                }

                if (listener != null) {
                    listener.onError(errorMsg, e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        String msg = "STT 서버 응답 오류 (HTTP " + response.code() + ")";
                        Log.e(TAG, "❌ " + msg);

                        // 상세 에러 메시지
                        if (response.code() == 500) {
                            msg += " - 서버 내부 오류";
                        } else if (response.code() == 503) {
                            msg += " - 서버 일시적 사용 불가";
                        } else if (response.code() == 400) {
                            msg += " - 잘못된 요청 형식";
                        }

                        if (listener != null) listener.onError(msg, null);
                        return;
                    }

                    String body = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "STT raw response: " + body);

                    if (body.isEmpty()) {
                        Log.e(TAG, "❌ Empty response from server");
                        if (listener != null) {
                            listener.onError("서버로부터 빈 응답을 받았습니다.", null);
                        }
                        return;
                    }

                    // JSON 파싱 및 검증
                    SttResult result = parseJsonResponse(body);

                    if (result == null) {
                        Log.e(TAG, "❌ Failed to parse JSON response");
                        if (listener != null) {
                            listener.onError("서버 응답 형식이 올바르지 않습니다.", null);
                        }
                        return;
                    }

                    // success 필드 확인
                    if (!result.success) {
                        String errorMsg = result.message != null ? result.message : "알 수 없는 오류";
                        Log.w(TAG, "⚠️ STT failed: " + errorMsg);
                        if (listener != null) {
                            listener.onError("음성 인식 실패: " + errorMsg, null);
                        }
                        return;
                    }

                    // 빈 텍스트 확인
                    if (result.text == null || result.text.trim().isEmpty()) {
                        Log.w(TAG, "⚠️ STT returned empty text");
                        if (listener != null) {
                            listener.onError("인식된 음성이 없습니다. 다시 말씀해주세요.", null);
                        }
                        return;
                    }

                    // 성공
                    Log.d(TAG, "✅ STT success: " + result.text);
                    if (listener != null) {
                        listener.onResult(result.text);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "❌ Error processing STT response", e);
                    if (listener != null) {
                        listener.onError("응답 처리 중 오류 발생: " + e.getMessage(), e);
                    }
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });
    }

    // STT 서버 응답 파싱 결과
    private static class SttResult {
        String text;
        boolean success;
        String message;
    }

    /**
     * JSON 응답 파싱 (간단한 수동 파싱)
     * 예상 형식: {"text": "...", "success": true/false, "message": "..."}
     */
    private SttResult parseJsonResponse(String json) {
        try {
            SttResult result = new SttResult();

            // text 필드 파싱
            int textIdx = json.indexOf("\"text\"");
            if (textIdx != -1) {
                int colon = json.indexOf(":", textIdx);
                int firstQuote = json.indexOf("\"", colon + 1);
                int secondQuote = json.indexOf("\"", firstQuote + 1);
                if (firstQuote != -1 && secondQuote != -1) {
                    result.text = json.substring(firstQuote + 1, secondQuote);
                }
            }

            // success 필드 파싱
            int successIdx = json.indexOf("\"success\"");
            if (successIdx != -1) {
                int colon = json.indexOf(":", successIdx);
                String afterColon = json.substring(colon + 1).trim();
                result.success = afterColon.startsWith("true");
            } else {
                // success 필드가 없으면 기본값 true (하위 호환성)
                result.success = true;
            }

            // message 필드 파싱
            int msgIdx = json.indexOf("\"message\"");
            if (msgIdx != -1) {
                int colon = json.indexOf(":", msgIdx);
                int firstQuote = json.indexOf("\"", colon + 1);
                int secondQuote = json.indexOf("\"", firstQuote + 1);
                if (firstQuote != -1 && secondQuote != -1) {
                    result.message = json.substring(firstQuote + 1, secondQuote);
                }
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "JSON parsing error", e);
            return null;
        }
    }

    @Deprecated
    private String parseTextFromJson(String json) {
        // 하위 호환성을 위해 남겨둠 (사용 안 함)
        try {
            int idx = json.indexOf("\"text\"");
            if (idx == -1) return json;
            int colon = json.indexOf(":", idx);
            int firstQuote = json.indexOf("\"", colon + 1);
            int secondQuote = json.indexOf("\"", firstQuote + 1);
            if (firstQuote == -1 || secondQuote == -1) return json;
            return json.substring(firstQuote + 1, secondQuote);
        } catch (Exception e) {
            Log.w(TAG, "parseTextFromJson error: " + e.getMessage());
            return json;
        }
    }

    /** PCM(raw) → WAV 헤더 붙여서 바이트 배열로 리턴 */
    private byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int totalDataLen = pcmData.length + 36;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // RIFF 헤더
            out.write("RIFF".getBytes());
            out.write(intToLittleEndian(totalDataLen));
            out.write("WAVE".getBytes());

            // fmt 서브청크
            out.write("fmt ".getBytes());
            out.write(intToLittleEndian(16)); // Subchunk1Size (16 for PCM)
            out.write(shortToLittleEndian((short) 1)); // AudioFormat = 1 (PCM)
            out.write(shortToLittleEndian((short) channels));
            out.write(intToLittleEndian(sampleRate));
            out.write(intToLittleEndian(byteRate));
            out.write(shortToLittleEndian((short) (channels * bitsPerSample / 8))); // BlockAlign
            out.write(shortToLittleEndian((short) bitsPerSample));

            // data 서브청크
            out.write("data".getBytes());
            out.write(intToLittleEndian(pcmData.length));
            out.write(pcmData);

            return out.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "pcmToWav error", e);
            return pcmData; // 최소 fallback
        }
    }

    private byte[] intToLittleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private byte[] shortToLittleEndian(short value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array();
    }
}