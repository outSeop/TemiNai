package com.example.teminai.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.example.teminai.R;
import com.example.teminai.ai.AbnormalAiClient;
import com.example.teminai.ai.AbnormalLastClient;
import com.example.teminai.bridge.TemiJsBridge;
import com.example.teminai.camera.CameraMonitorService;
import com.example.teminai.camera.CameraWebSocketMonitor;
import com.example.teminai.network.AbnormalWebSocketClient;
import com.example.teminai.network.TemiServerClient;
import com.example.teminai.stt.SttRecorderClient;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

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

    // WebSocket 방식
    private AbnormalWebSocketClient wsClient;
    private CameraWebSocketMonitor wsMonitor;

    private Map<String, String> map;   // 부스 ID → Temi location name

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        // Temi SDK 인스턴스
        robot = Robot.getInstance();
        webView = findViewById(R.id.webview);

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
}