package com.example.teminai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.SttRequest;
import com.robotemi.sdk.SttLanguage;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.voice.WakeupRequest;


import java.util.Collections;

import java.util.HashMap;
import java.util.Map;

// TODO
// https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip 에서 로컬 모델 다운
// TTS

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_CAMERA_PERMISSION = 1001;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private Robot robot;
    private TemiSpeechManager speechManager;
    private TemiBridge temiBridge;
    private VoskSpeechManager vosk;
    private ServerSttClient serverSttClient;

    Map<String, String> map;

    static {
        try {
            System.loadLibrary("vosk");
            Log.d("VOSK_CHECK", "libvosk.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e("VOSK_CHECK", "Failed to load libvosk.so: " + e.getMessage(), e);
        } catch (Throwable t) {
            // 혹시나 다른 에러까지 다 보기
            Log.e("VOSK_CHECK", "Unexpected error while loading libvosk.so", t);
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        setContentView(R.layout.activity_main);

        // Temi SDK 인스턴스
        robot = Robot.getInstance();
        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        map = initIdToLocationName();

        // Temi 음성 / 브리지 매니저 초기화
        speechManager = new TemiSpeechManager(robot, webView);
        temiBridge = new TemiBridge();

        // JS <-> Android 브리지 등록
        webView.addJavascriptInterface(temiBridge, "TemiInterface");

        String sttServerUrl = "qlak315.iptime.org:20330/stt"; // TODO: 너 서버 주소로 변경
        serverSttClient = new ServerSttClient(webView, sttServerUrl);


        // 디버그 모드에서 WebView 디버깅 허용
        WebView.setWebContentsDebuggingEnabled(true);

        // Vite 빌드 결과(dist)를 assets/web 아래에 넣고
        // https://appassets.androidplatform.net/web/index.html 로 매핑
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
                // WebRTC getUserMedia 등에서 오는 권한 요청 자동 허용
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        request.grant(request.getResources());
                        Log.d(TAG, "onPermissionRequest from: " + request.getOrigin().toString());
                    }
                });
            }

        });

        // JS <-> Android 브리지 추가 (window.Temi로 접근)
        webView.addJavascriptInterface(temiBridge, "Temi");

        // 카메라 퍼미션 체크 & 요청
        ensureCameraPermission();
        ensureMediaPermissions();
        // Vite 빌드된 index.html 로딩
        String url = "https://appassets.androidplatform.net/web/index.html";
        Log.d(TAG, "Loading URL: " + url);
        webView.loadUrl(url);
    }
    private void sendSpeechToJs(String text) {
        String safe = text.replace("\"", "\\\"");
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.receiveSpeech && window.receiveSpeech(\"" + safe + "\");",
                null
        ));
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 필요하면 Temi 리스너들 여기서 등록 (goTo 상태 등)
        // 예시:
        // robot.addOnGoToLocationStatusChangedListener((location, status) -> {
        //     Log.d(TAG, "GoTo " + location + " status: " + status);
        // });

        // STT 결과 수신 리스너 등록
        if (robot != null && speechManager != null) {
            robot.addAsrListener(speechManager);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 등록한 리스너 정리
        if (robot != null && speechManager != null) {
            robot.removeAsrListener(speechManager);
        }
        // robot.removeOnGoToLocationStatusChangedListener(...); 등을 호출하면 됨
    }

    // 안드로이드 하드웨어 "뒤로가기" 버튼 처리
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            // WebView 안에 히스토리가 있으면 그쪽으로 먼저 이동
            webView.goBack();
            return;
        }

        // 더 이상 갈 데 없을 때:
        // 1) 홈으로 나가게 하고 싶으면 ↓ 유지
        super.onBackPressed();

        // 2) 홈으로 나가지 않고 무시하고 싶으면 위 한 줄 지우고 그냥 return; 하면 됨
        // return;
    }

    // 카메라 권한 체크 & 요청
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

    private static final int REQ_MEDIA_PERMISSIONS = 1001;

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
                // 필요하면 여기서 JS로 알림 보내거나 토스트 띄워도 됨
            }
        }
    }


    // 길찾기 매핑
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

    /**
     * JS에서 Temi 제어를 위한 브리지
     * React 쪽에서 window.Temi.speak("문구"), window.Temi.goTo("위치"등으로 호출
     */
    public class TemiBridge {
        /**
         * JS에서 Temi 제어를 위한 브리지
         * React 쪽에서 window.TemiInterface.speak("문구"),
         * window.TemiInterface.goTo("위치"),
         * window.TemiInterface.startListening() 으로 사용
         */

        @JavascriptInterface
        public void speak(String text) {
            Log.d(TAG, "Temi.speak called from JS: " + text);
            if (robot != null) {
                runOnUiThread(() -> robot.speak(TtsRequest.create(text, false)));
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

        public String convertLocationID(String locationID) {
            if (locationID == null) return null;

            if (map.containsKey(locationID)) {
                return map.get(locationID);
            }

            return null;
        }
        @JavascriptInterface
        public void goTo(String location) {
            Log.d(TAG, "Temi.goTo called from JS: " + location);
            if (robot != null) {
                runOnUiThread(() -> robot.goTo(location));
            }
            Log.d(TAG, "Finished goTo: "+ location);
        }

        // 🔊 JS에서 호출하는 마이크 시작 함수
        @JavascriptInterface
        public void startListening() {
            Log.d(TAG, "Temi.startListening (SDK STT) called from JS");

            if (speechManager == null) {
                Log.e(TAG, "TemiSpeechManager is null, cannot start STT");
                return;
            }

            speechManager.startListening();
        }

        // JS로 에러 전송
        private void sendJsError(String message) {
            String safe = message.replace("\"", "\\\"");
            String js = "window.onTemiError && window.onTemiError(\"" + safe + "\");";
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        }
    }

    public class TemiSpeechManager implements Robot.AsrListener {

        private final Robot robot;
        private final WebView webView;
        private static final String TAG = "TemiSpeechManager";

        public TemiSpeechManager(Robot robot, WebView webView) {
            this.robot = robot;
            this.webView = webView;
        }

        /** JS에서 마이크 시작 버튼 눌렀을 때 호출될 함수 */
        public void startListening() {
            if (robot == null) {
                sendSpeechErrorToJs("Temi 로봇 인스턴스가 없습니다.");
                return;
            }
            vosk.startListening();
        }

        @Override
        public void onAsrResult(@NonNull String asrResult, @NonNull SttLanguage sttLanguage) {
            Log.d("TemiSpeechManager", "STT Result: " + asrResult);
            // JS로 결과 보내기 (필요 시)
            sendSpeechResultToJs(asrResult);
        }

        private void sendSpeechErrorToJs(String msg) {
            String safeText = msg.replace("\"", "\\\"");
            String js = "console.log(\"[Temi STT Error] " + safeText + "\");";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }

        private void sendSpeechResultToJs(String text) {
            String safeText = text.replace("\"", "\\\"");
            String js = "window.receiveSpeech && window.receiveSpeech(\"" + safeText + "\");";
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        }    }
}