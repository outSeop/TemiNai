package com.example.teminai.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.example.teminai.PhotoBoothCameraActivity;
import com.example.teminai.R;
import com.example.teminai.ai.AbnormalAiClient;
import com.example.teminai.ai.AbnormalLastClient;
import com.example.teminai.bridge.TemiJsBridge;
import com.example.teminai.camera.CameraMonitorService;
import com.example.teminai.camera.CameraOverlayView;
import com.example.teminai.camera.CameraWebSocketMonitor;
import com.example.teminai.network.AbnormalWebSocketClient;
import com.example.teminai.network.TemiServerClient;
import com.example.teminai.stt.SttRecorderClient;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_CAMERA_PERMISSION = 1001;
    private static final int REQ_MEDIA_PERMISSIONS = 1002;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private Robot robot;

    private TemiServerClient temiServerClient;
    private SttRecorderClient sttClient;
    private AbnormalAiClient abnormalClient;
    private TemiJsBridge temiJsBridge;
    private AbnormalLastClient abnormalLastClient;
    private CameraMonitorService cameraMonitor;
    private CameraOverlayView cameraView;
    private FrameLayout cameraContainer;
    private android.widget.TextView countdownTextView; // 카운트다운 표시용 TextView
    private static final int REQ_PHOTO_SEQUENCE = 2001;
    private ActivityResultLauncher<Intent> photoSequenceLauncher;
    // WebSocket 방식
    private AbnormalWebSocketClient wsClient;
    private CameraWebSocketMonitor wsMonitor;

    private Map<String, String> map;   // 부스 ID → Temi location name

    // Temi 이동 상태 리스너
    private final OnGoToLocationStatusChangedListener goToListener = new OnGoToLocationStatusChangedListener() {
        @Override
        public void onGoToLocationStatusChanged(
                String location,
                String status,
                int descriptionId,
                String description
        ) {
            Log.d(TAG, "🚙 Temi Navigation Event - Location: " + location + ", Status: " + status);

            // React로 이벤트 전달
            runOnUiThread(() -> {
                String js = String.format(
                    "if (window.TemiInterface && window.TemiInterface._goToListener) { " +
                    "  window.TemiInterface._goToListener({location: '%s', status: '%s', description: '%s'}); " +
                    "}",
                    location.replace("'", "\\'"),
                    status.replace("'", "\\'"),
                    description.replace("'", "\\'")
                );
                webView.evaluateJavascript(js, null);
            });
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        // Temi SDK 인스턴스
        robot = Robot.getInstance();
        webView = findViewById(R.id.webview);
        cameraContainer = findViewById(R.id.camera_container);

        // Temi 이동 상태 리스너 등록
        robot.addOnGoToLocationStatusChangedListener(goToListener);
        Log.d(TAG, "✅ OnGoToLocationStatusChangedListener registered");

        // WebView 설정
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        WebView.setWebContentsDebuggingEnabled(true);

        // 길찾기 매핑 데이터
        map = initIdToLocationName();

        // ----- 서버 클라이언트 초기화 -----
        // 공통 base URL
        String baseUrl = "http://qlak315.iptime.org:20330";
        temiServerClient = new TemiServerClient(baseUrl);
        abnormalLastClient = new AbnormalLastClient(
                temiServerClient,
                "/abnormal/last",
                new AbnormalLastClient.Listener() {
                    @Override
                    public void onResult(boolean isAbnormal,
                                         String label,
                                         double score,
                                         String time,
                                         String camera) {
                        // JS 콜백 호출
                        String safeLabel = label == null ? "" : label.replace("\"", "\\\"");
                        String safeTime = time == null ? "" : time.replace("\"", "\\\"");
                        String safeCamera = camera == null ? "" : camera.replace("\"", "\\\"");

                        String js = "window.onAbnormalResult && window.onAbnormalResult("
                                + (isAbnormal ? "true" : "false") + ","
                                + "\"" + safeLabel + "\","
                                + score + ","
                                + "\"" + safeTime + "\","
                                + "\"" + safeCamera + "\""
                                + ");";

                        runOnUiThread(() -> webView.evaluateJavascript(js, null));
                    }

                    @Override
                    public void onError(String message, Throwable t) {
                        Log.w("MainActivity", "AbnormalLastClient error: " + message, t);
                    }
                }
        );
        class JsBridge {

            /** inline preview 시작 */
            @JavascriptInterface
            public void startInlinePreview() {
                Log.d(TAG, "[JsBridge] 📹 startInlinePreview 호출됨");
                runOnUiThread(() -> {
                    if (cameraView == null) {
                        Log.d(TAG, "[JsBridge] 🆕 새 CameraOverlayView 생성");
                        cameraView = new CameraOverlayView(MainActivity.this);
                        cameraContainer.addView(cameraView,
                                new FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                ));
                    } else {
                        Log.d(TAG, "[JsBridge] ✅ 기존 CameraOverlayView 재사용");
                    }

                    // 카운트다운 TextView 생성
                    if (countdownTextView == null) {
                        Log.d(TAG, "[JsBridge] 🆕 카운트다운 TextView 생성");
                        countdownTextView = new android.widget.TextView(MainActivity.this);
                        countdownTextView.setTextSize(200f);
                        countdownTextView.setTextColor(0xFFFFFFFF); // 흰색
                        countdownTextView.setGravity(android.view.Gravity.CENTER);
                        countdownTextView.setShadowLayer(10f, 0f, 0f, 0xFF000000); // 검은색 그림자
                        countdownTextView.setBackgroundColor(0x80000000); // 반투명 검은색 배경
                        countdownTextView.setVisibility(android.view.View.GONE);
                        countdownTextView.setTypeface(null, android.graphics.Typeface.BOLD);

                        cameraContainer.addView(countdownTextView,
                                new FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                ));
                    }

                    cameraContainer.setVisibility(android.view.View.VISIBLE);
                    Log.d(TAG, "[JsBridge] ✅ 카메라 컨테이너 VISIBLE 설정");
                });
            }

            /** inline preview 숨기기 */
            @JavascriptInterface
            public void hideInlinePreview() {
                Log.d(TAG, "[JsBridge] 🙈 hideInlinePreview 호출됨");
                runOnUiThread(() -> {
                    cameraContainer.setVisibility(android.view.View.GONE);
                    if (cameraView != null) {
                        Log.d(TAG, "[JsBridge] 🛑 카메라 프리뷰 중지");
                        cameraView.stop();
                    }
                    Log.d(TAG, "[JsBridge] ✅ 카메라 컨테이너 GONE 설정");
                });
            }

            /** 한 장 촬영 (5초 카운트다운 포함) */
            @JavascriptInterface
            public void capturePhoto() {
                Log.d(TAG, "[JsBridge] 📸 capturePhoto 호출됨");
                new Thread(() -> {
                    try {
                        Log.d(TAG, "[JsBridge] ⏱️ 5초 카운트다운 시작");
                        // 5초 카운트다운
                        for (int i = 5; i > 0; i--) {
                            final int count = i;
                            Log.d(TAG, "[JsBridge] ⏱️ 카운트다운: " + count);
                            runOnUiThread(() -> {
                                // WebView에 전달 (React)
                                String js = "window.temiOnCountdown && window.temiOnCountdown(" + count + ");";
                                webView.evaluateJavascript(js, null);

                                // TextView에 카운트다운 표시 (네이티브)
                                if (countdownTextView != null) {
                                    countdownTextView.setText(String.valueOf(count));
                                    countdownTextView.setVisibility(android.view.View.VISIBLE);
                                }
                            });
                            Thread.sleep(1000);
                        }

                        // 카운트다운 종료
                        Log.d(TAG, "[JsBridge] ⏱️ 카운트다운 종료");
                        runOnUiThread(() -> {
                            String js = "window.temiOnCountdown && window.temiOnCountdown(null);";
                            webView.evaluateJavascript(js, null);

                            // TextView에서 카운트다운 숨기기
                            if (countdownTextView != null) {
                                countdownTextView.setVisibility(android.view.View.GONE);
                            }
                        });

                        // 실제 촬영
                        Log.d(TAG, "[JsBridge] 📷 실제 촬영 시작");
                        runOnUiThread(() -> {
                            if (cameraView != null) {
                                Log.d(TAG, "[JsBridge] ✅ cameraView.capture() 호출");
                                cameraView.capture((data, cam) -> {
                                    Bitmap bitmap = null;
                                    Bitmap rotated = null;
                                    Bitmap flipped = null;
                                    Bitmap resized = null;
                                    FileOutputStream fos = null;
                                    java.io.ByteArrayOutputStream baos = null;

                                    try {
                                        Log.d(TAG, "[JsBridge] 📸 사진 캡처 완료, JPEG 크기: " + data.length + " bytes");

                                        // JPEG 데이터를 Bitmap으로 변환
                                        bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                                        Log.d(TAG, "[JsBridge] 🖼️ Bitmap 변환 완료: " + bitmap.getWidth() + "x" + bitmap.getHeight());

                                        // 90도 회전 (카메라가 180도 뒤집힌 상태 보정)
                                        Log.d(TAG, "[JsBridge] 🔄 이미지 90도 회전 시작");
                                        Matrix matrix = new Matrix();
                                        matrix.postRotate(90);
                                        rotated = Bitmap.createBitmap(
                                                bitmap, 0, 0,
                                                bitmap.getWidth(), bitmap.getHeight(),
                                                matrix, true
                                        );
                                        Log.d(TAG, "[JsBridge] ✅ 회전 완료: " + rotated.getWidth() + "x" + rotated.getHeight());

                                        // 좌우 반전 (front camera 미러 효과)
                                        Log.d(TAG, "[JsBridge] 🔄 좌우 반전 시작");
                                        Matrix flipMatrix = new Matrix();
                                        flipMatrix.preScale(-1.0f, 1.0f);
                                        flipped = Bitmap.createBitmap(
                                                rotated, 0, 0,
                                                rotated.getWidth(), rotated.getHeight(),
                                                flipMatrix, true
                                        );
                                        Log.d(TAG, "[JsBridge] ✅ 좌우 반전 완료");

                                        // 리사이즈 (OOM 방지 - 최대 폭 1200px)
                                        Log.d(TAG, "[JsBridge] 📏 리사이즈 시작");
                                        int maxWidth = 1200;
                                        if (flipped.getWidth() > maxWidth) {
                                            float ratio = (float) maxWidth / flipped.getWidth();
                                            int newHeight = (int) (flipped.getHeight() * ratio);
                                            resized = Bitmap.createScaledBitmap(flipped, maxWidth, newHeight, true);
                                            Log.d(TAG, "[JsBridge] ✅ 리사이즈 완료: " + maxWidth + "x" + newHeight);
                                        } else {
                                            resized = flipped;
                                            flipped = null; // resized가 참조하므로 null 처리
                                            Log.d(TAG, "[JsBridge] ℹ️ 리사이즈 불필요 (이미 " + resized.getWidth() + "px)");
                                        }

                                        // Bitmap을 JPEG로 저장 (디버깅용)
                                        File file = new File(getCacheDir(),
                                                "photo_" + System.currentTimeMillis() + ".jpg");

                                        fos = new FileOutputStream(file);
                                        resized.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                                        fos.close();
                                        Log.d(TAG, "[JsBridge] 💾 파일 저장 완료: " + file.getAbsolutePath());

                                        // Bitmap을 Base64로 인코딩
                                        Log.d(TAG, "[JsBridge] 🔄 Base64 인코딩 시작");
                                        baos = new java.io.ByteArrayOutputStream();
                                        resized.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                                        byte[] imageBytes = baos.toByteArray();
                                        String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                                        String dataUrl = "data:image/jpeg;base64," + base64Image;
                                        baos.close();
                                        Log.d(TAG, "[JsBridge] ✅ Base64 인코딩 완료, 크기: " + dataUrl.length() + " chars");

                                        // JSON을 사용한 안전한 전달
                                        JSONObject json = new JSONObject();
                                        json.put("dataUrl", dataUrl);
                                        String js = "window.temiOnPhotoCaptured && window.temiOnPhotoCaptured(" + json.getString("dataUrl") + ");";

                                        Log.d(TAG, "[JsBridge] 📤 React로 Base64 dataURL 전달");
                                        webView.evaluateJavascript(js, null);

                                        cam.startPreview();
                                        Log.d(TAG, "[JsBridge] ✅ 카메라 프리뷰 재시작");
                                    } catch (Exception e) {
                                        Log.e(TAG, "[JsBridge] ❌ 사진 처리 중 오류", e);
                                        e.printStackTrace();
                                    } finally {
                                        // 메모리 해제 (finally 블록에서 안전하게 처리)
                                        if (bitmap != null && !bitmap.isRecycled()) {
                                            bitmap.recycle();
                                        }
                                        if (rotated != null && !rotated.isRecycled()) {
                                            rotated.recycle();
                                        }
                                        if (flipped != null && !flipped.isRecycled()) {
                                            flipped.recycle();
                                        }
                                        if (resized != null && !resized.isRecycled()) {
                                            resized.recycle();
                                        }
                                        if (fos != null) {
                                            try { fos.close(); } catch (Exception ignore) {}
                                        }
                                        if (baos != null) {
                                            try { baos.close(); } catch (Exception ignore) {}
                                        }
                                        Log.d(TAG, "[JsBridge] 🧹 Bitmap 메모리 해제 완료");
                                    }
                                });
                            } else {
                                Log.e(TAG, "[JsBridge] ❌ cameraView가 null!");
                            }
                        });

                    } catch (InterruptedException e) {
                        Log.e(TAG, "[JsBridge] ❌ 카운트다운 중 인터럽트", e);
                        e.printStackTrace();
                    }
                }).start();
            }
        }
        // STT 클라이언트
        sttClient = new SttRecorderClient(
                temiServerClient,
                "/stt",
                new SttRecorderClient.Listener() {
                    @Override
                    public void onResult(String text) {
                        Log.d(TAG, "STT Result: " + text);
                        sendSpeechToJs(text);
                    }

                    @Override
                    public void onError(String message, Throwable t) {
                        Log.e(TAG, "[Server STT Error] " + message, t);
                        String safe = message.replace("\"", "\\\"");
                        runOnUiThread(() -> webView.evaluateJavascript(
                                "console.log(\"[Server STT Error] " + safe + "\");",
                                null
                        ));
                    }
                }
        );

        abnormalClient = new AbnormalAiClient(
                temiServerClient,
                "/abnormal",   // base path
                new AbnormalAiClient.Listener() {
                    @Override
                    public void onResult(boolean isAbnormal, String label, double score) {
                        // 이미 백그라운드 스레드에서 호출되므로 runOnUiThread로 감싸기
                        runOnUiThread(() -> {
                            String safeLabel = (label == null) ? "" : label.replace("\"", "\\\"");

                            String js = String.format(
                                    "window.onAbnormalResult && window.onAbnormalResult(%s, \"%s\", %f);",
                                    isAbnormal ? "true" : "false",
                                    safeLabel,
                                    score
                            );

                            Log.d(TAG, "🔔 Sending result to React: isAbnormal=" + isAbnormal + ", label=" + label);
                            webView.evaluateJavascript(js, null);
                        });
                    }

                    @Override
                    public void onError(String message, Throwable t) {
                        String safeMsg = (message == null) ? "" : message.replace("\"", "\\\"");
                        String js = String.format(
                                "window.onAbnormalError && window.onAbnormalError(\"%s\");",
                                safeMsg
                        );
                        Log.w(TAG, "⚠️ Sending error to React: " + message);
                        runOnUiThread(() -> webView.evaluateJavascript(js, null));
                    }
                }
        );

        // ========== WebSocket 방식 (NEW - 빠른 스트리밍) ==========
        wsClient = new AbnormalWebSocketClient(
                temiServerClient.getHttpClient(),
                baseUrl,
                new AbnormalWebSocketClient.Listener() {
                    @Override
                    public void onAnalysisResult(boolean isAbnormal, String label, double score) {
                        Log.d(TAG, "🔔 WebSocket analysis result: " + isAbnormal + ", " + label);
                        runOnUiThread(() -> {
                            String safeLabel = (label == null) ? "" : label.replace("\"", "\\\"");
                            String js = String.format(
                                    "window.onAbnormalResult && window.onAbnormalResult(%s, \"%s\", %f);",
                                    isAbnormal ? "true" : "false",
                                    safeLabel,
                                    score
                            );
                            webView.evaluateJavascript(js, null);
                        });
                    }

                    @Override
                    public void onBufferStatus(int bufferSize, int requiredSize, boolean bufferReady) {
                        if (bufferSize % 10 == 0 || bufferSize == 1) {
                            Log.d(TAG, "📊 Buffer: " + bufferSize + "/" + requiredSize);
                        }
                    }

                    @Override
                    public void onError(String message, Throwable t) {
                        Log.e(TAG, "❌ WebSocket error: " + message, t);
                        runOnUiThread(() -> {
                            String safe = (message == null) ? "" : message.replace("\"", "\\\"");
                            String js = "window.onAbnormalError && window.onAbnormalError(\"" + safe + "\");";
                            webView.evaluateJavascript(js, null);
                        });
                    }

                    @Override
                    public void onConnected() {
                        Log.d(TAG, "✅ WebSocket connected");
                    }

                    @Override
                    public void onDisconnected() {
                        Log.d(TAG, "❌ WebSocket disconnected");
                    }
                }
        );

        wsMonitor = new CameraWebSocketMonitor(
                this,
                wsClient,
                new CameraWebSocketMonitor.Listener() {
                    @Override
                    public void onFrameSent(int frameCount) {
                        Log.d(TAG, "📤 Frame #" + frameCount + " sent via WebSocket");
                        runOnUiThread(() -> {
                            String js = "window.onFrameSent && window.onFrameSent(" + frameCount + ");";
                            webView.evaluateJavascript(js, null);
                        });
                    }

                    @Override
                    public void onAnalysisResult(boolean isAbnormal, String label, double score) {
                        // wsClient 콜백에서 처리됨
                    }

                    @Override
                    public void onBufferProgress(int current, int required) {
                        // wsClient 콜백에서 처리됨
                    }

                    @Override
                    public void onError(String message, Throwable t) {
                        Log.e(TAG, "❌ Camera WebSocket error: " + message, t);
                        runOnUiThread(() -> {
                            String safe = (message == null) ? "" : message.replace("\"", "\\\"");
                            String js = "window.onCameraError && window.onCameraError(\"" + safe + "\");";
                            webView.evaluateJavascript(js, null);
                        });
                    }
                }
        );

        // ========== HTTP 방식 (기존 - 백업용) ==========
        cameraMonitor = new CameraMonitorService(
                this,
                abnormalClient,
                new CameraMonitorService.Listener() {
                    @Override
                    public void onFrameSent(int frameCount) {
                        if (frameCount % 10 == 0 || frameCount == 1) {
                            Log.d(TAG, "📤 Frame #" + frameCount + " sent to server");
                            String js = "window.onFrameSent && window.onFrameSent(" + frameCount + ");";
                            runOnUiThread(() -> webView.evaluateJavascript(js, null));
                        }
                    }

                    @Override
                    public void onError(String message, Throwable t) {
                        Log.e(TAG, "❌ Camera monitor error: " + message, t);
                        String safe = (message == null) ? "" : message.replace("\"", "\\\"");
                        String js = "window.onCameraError && window.onCameraError(\"" + safe + "\");";
                        runOnUiThread(() -> webView.evaluateJavascript(js, null));
                    }
                }
        );

        // ----- JS 브리지 등록 -----
        temiJsBridge = new TemiJsBridge(
                this,
                robot,
                webView,
                sttClient,
                abnormalClient,
                abnormalLastClient,
                cameraMonitor,
                wsMonitor,  // WebSocket 모니터 추가
                map
        );
        webView.addJavascriptInterface(temiJsBridge, "TemiInterface");

        // 포토부스 카메라 브리지 등록
        webView.addJavascriptInterface(new JsBridge(), "Android");

        // assets/web → https://appassets.androidplatform.net/web/ 매핑
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/web/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                return assetLoader.shouldInterceptRequest(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d(TAG, "Page started loading: " + url);
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page finished loading: " + url);
                super.onPageFinished(view, url);

                // 페이지 로딩 완료 → JS 함수 자동 실행
                view.evaluateJavascript("window.onPageLoaded && window.onPageLoaded();", null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d("WebViewConsole",
                        consoleMessage.message() + "  -- From line "
                                + consoleMessage.lineNumber() + " of "
                                + consoleMessage.sourceId());
                return super.onConsoleMessage(consoleMessage);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        request.grant(request.getResources());
                        Log.d(TAG, "onPermissionRequest from: " + request.getOrigin().toString());
                    }
                });
            }
        });

        // 권한 체크
        Log.d(TAG, "========== CHECKING PERMISSIONS ==========");
        ensureCameraPermission();
        ensureMediaPermissions();
        logPermissionStatus();
        Log.d(TAG, "========================================");

        // Vite 빌드된 index.html 로딩
        String url = "https://appassets.androidplatform.net/web/index.html";
        Log.d(TAG, "Loading URL: " + url);
        webView.loadUrl(url);
    }

    /** JS 쪽으로 STT 텍스트 전달 */
    private void sendSpeechToJs(String text) {
        String safe = text.replace("\"", "\\\"");
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.receiveSpeech && window.receiveSpeech(\"" + safe + "\");",
                null
        ));
    }

    // ---------------- 권한 ----------------

    private void ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA_PERMISSION
            );
        } else {
            Log.d(TAG, "Camera permission already granted");
        }
    }

    private void ensureMediaPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };

        boolean needRequest = false;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, REQ_MEDIA_PERMISSIONS);
        } else {
            Log.d(TAG, "Media permissions already granted");
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission granted");
            } else {
                Log.d(TAG, "Camera permission denied");
            }
        }
    }

    // ---------------- 생명주기 ----------------

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause - 카메라 리소스 해제");
        // 카메라 프리뷰 정리
        if (cameraView != null) {
            cameraView.stop();
        }
        // 카운트다운 TextView 숨김
        if (countdownTextView != null) {
            countdownTextView.setVisibility(android.view.View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        // 필요 시 카메라 재시작 로직 추가 가능
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy - 리소스 정리");

        // 카메라 정리
        if (cameraView != null) {
            cameraView.stop();
            cameraView = null;
        }

        // Temi 리스너 해제
        if (robot != null) {
            robot.removeOnGoToLocationStatusChangedListener(goToListener);
            Log.d(TAG, "🧹 OnGoToLocationStatusChangedListener removed");
        }
    }

    // ---------------- 뒤로가기 ----------------

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 위치 ID 매핑 ----------------

    private Map<String, String> initIdToLocationName() {
        Map<String, String> map = new HashMap<>();
        map.put("immersive media", "immersive media");
        map.put("data security", "data security");
        map.put("future car", "future car");
        map.put("secondary battery", "secondary battery");
        map.put("bio health", "bio health");
        map.put("intelligent robot", "intelligent robot");
        map.put("new energy business", "new energy business");
        map.put("big-data", "big-data");
        map.put("next generation displayer", "next generation displayer");
        map.put("ai", "ai");
        map.put("next generation communications", "communications");
        map.put("advanced materials", "advanced materials");
        map.put("next generation semiconductor", "NGsemiconductor");
        map.put("green bio", "green bio");
        map.put("internet of things", "internet of things");
        map.put("semiconductor department manager", "semiconductorDM");
        map.put("aviation drone", "aviation drone");
        map.put("rest area 1", "rest area 1");
        map.put("rest area 2", "rest area 2");
        return map;
    }

    private void logPermissionStatus() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        Log.d(TAG, "📹 CAMERA permission: " + (cameraGranted ? "✅ GRANTED" : "❌ DENIED"));
        Log.d(TAG, "🎤 RECORD_AUDIO permission: " + (audioGranted ? "✅ GRANTED" : "❌ DENIED"));

        if (!cameraGranted || !audioGranted) {
            Log.w(TAG, "⚠️  Some permissions are missing! WebRTC/Camera may not work properly.");
        }
    }

    // JS 브릿지에서 호출됨
    public void launchPhotoBoothCamera() {
        Intent intent = new Intent(this, PhotoBoothCameraActivity.class);
        startActivityForResult(intent, REQ_PHOTO_SEQUENCE);
    }

}