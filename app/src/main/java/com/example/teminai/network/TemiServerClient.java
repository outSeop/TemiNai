package com.example.teminai.network;

import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

public class TemiServerClient {

    private final String baseUrl;
    private final OkHttpClient httpClient;

    public TemiServerClient(String baseUrl) {
        // 마지막 슬래시는 제거해두자 ("/stt" 이런 식으로 붙일 거라)
        if (baseUrl.endsWith("/")) {
            this.baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        } else {
            this.baseUrl = baseUrl;
        }
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)     // 연결 타임아웃: 10초
                .readTimeout(30, TimeUnit.SECONDS)        // 읽기 타임아웃: 30초 (STT는 시간 걸림)
                .writeTimeout(30, TimeUnit.SECONDS)       // 쓰기 타임아웃: 30초
                .retryOnConnectionFailure(true)           // 연결 실패 시 재시도
                .build();
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public String buildUrl(String path) {
        if (path == null || path.isEmpty()) return baseUrl;
        if (!path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }
}
