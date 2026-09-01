package com.lingyao.platform.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * 抽象基类：封装 OkHttp + ObjectMapper 公共逻辑
 *
 * 子类只需实现 3 个抽象方法即可
 */
public abstract class AbstractSsoProvider implements SsoProvider {

    protected static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build();

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * GET 请求拿 JSON
     */
    protected JsonNode doGetJson(String url) throws Exception {
        Request request = new Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "lingyao-platform/2.0.19")
            .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code() + ": " + response.body().string());
            }
            String body = response.body().string();
            return MAPPER.readTree(body);
        }
    }

    /**
     * POST form-encoded 拿 JSON
     */
    protected JsonNode doPostFormJson(String url, java.util.Map<String, String> params) throws Exception {
        okhttp3.FormBody.Builder formBuilder = new okhttp3.FormBody.Builder();
        params.forEach(formBuilder::add);
        Request request = new Request.Builder()
            .url(url)
            .post(formBuilder.build())
            .header("User-Agent", "lingyao-platform/2.0.19")
            .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code() + ": " + response.body().string());
            }
            String body = response.body().string();
            return MAPPER.readTree(body);
        }
    }
}