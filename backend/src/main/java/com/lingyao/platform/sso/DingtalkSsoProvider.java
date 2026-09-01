package com.lingyao.platform.sso;

import com.lingyao.platform.entity.CompanySsoConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉 OAuth Provider
 *
 * 文档：
 * - 授权 URL: https://login.dingtalk.com/oauth2/auth?redirect_uri=xxx&response_type=code&client_id=xxx&scope=xxx&state=xxx&prompt=consent
 * - token:    POST https://api.dingtalk.com/v1.0/oauth2/userAccessToken
 * - user_info: GET https://api.dingtalk.com/v1.0/contact/users/me
 *
 * 钉钉 user_info 需要 unionid 调用 unionid2userid 接口转换（可选步骤）
 */
@Component
public class DingtalkSsoProvider extends AbstractSsoProvider {

    @Override
    public CompanySsoConfig.Provider getName() {
        return CompanySsoConfig.Provider.DINGTALK;
    }

    @Override
    public String buildAuthorizeUrl(CompanySsoConfig config, String state) {
        String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
            ? config.getBaseUrl()
            : "https://login.dingtalk.com";
        String scope = config.getScope() != null && !config.getScope().isBlank()
            ? config.getScope()
            : "openid";
        String clientId = config.getAppId();

        return baseUrl + "/oauth2/auth"
            + "?redirect_uri=" + URLEncoder.encode(config.getRedirectUri(), StandardCharsets.UTF_8)
            + "&response_type=code"
            + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
            + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
            + "&prompt=consent";
    }

    @Override
    public SsoToken exchangeCode(String code, CompanySsoConfig config) {
        try {
            String apiBase = "https://api.dingtalk.com";
            Map<String, String> params = new HashMap<>();
            params.put("clientId", config.getAppId());
            params.put("clientSecret", config.getAppSecretEnc());
            params.put("code", code);
            params.put("grantType", "authorization_code");

            JsonNode resp = doPostFormJson(apiBase + "/v1.0/oauth2/userAccessToken", params);

            if (!resp.has("accessToken")) {
                throw new RuntimeException("钉钉 token 失败: " + resp.toString());
            }
            return new SsoToken(
                resp.path("accessToken").asText(),
                resp.path("refreshToken").asText(null),
                resp.path("expireIn").asLong(7200),
                "Bearer"
            );
        } catch (Exception e) {
            throw new RuntimeException("钉钉 exchangeCode 异常: " + e.getMessage(), e);
        }
    }

    @Override
    public SsoUserInfo fetchUserInfo(SsoToken token, CompanySsoConfig config) {
        try {
            String apiBase = "https://api.dingtalk.com";
            okhttp3.Request request = new okhttp3.Request.Builder()
                .url(apiBase + "/v1.0/contact/users/me")
                .get()
                .addHeader("x-acs-dingtalk-access-token", token.getAccessToken())
                .build();

            JsonNode resp;
            try (okhttp3.Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("钉钉 user_info HTTP " + response.code());
                }
                resp = MAPPER.readTree(response.body().string());
            }

            // 钉钉 contact/users/me 返回 nick / unionId / openId / mobile / email / avatar
            // 部门 deptIds 不在 me 接口里，需要 contact/departments 列表另调
            String nick = resp.path("nick").asText(null);
            String unionId = resp.path("unionId").asText(null);
            String openId = resp.path("openId").asText(null);

            return new SsoUserInfo(
                openId,            // 钉钉用 openId 作为 employeeId（待转换 unionId2userid）
                unionId,           // 三方原始 ID 存 unionId（跨企业唯一）
                nick,
                resp.path("email").asText(null),
                resp.path("mobile").asText(null),
                List.of(),
                resp.path("avatar").asText(null)
            );
        } catch (Exception e) {
            throw new RuntimeException("钉钉 fetchUserInfo 异常: " + e.getMessage(), e);
        }
    }
}