package com.lingyao.platform.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingyao.platform.entity.CompanySsoConfig;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 OIDC Provider（接 Okta / Auth0 / Azure AD / Keycloak 等）
 *
 * 配置要点：
 * - appId = client_id
 * - appSecretEnc = client_secret
 * - baseUrl = issuer URL（如 https://login.microsoftonline.com/{tenant}/v2.0）
 * - redirectUri = callback URL
 * - extra_config 存 {"user_info_endpoint": "/oidc/userinfo"} 等可选项
 *
 * 流程符合 OIDC 标准：
 * 1) /authorize 跳转
 * 2) /token POST 拿 access_token + id_token
 * 3) /userinfo GET 拿用户信息（带 access_token）
 */
@Component
public class GenericOidcSsoProvider extends AbstractSsoProvider {

    @Override
    public CompanySsoConfig.Provider getName() {
        return CompanySsoConfig.Provider.GENERIC_OIDC;
    }

    @Override
    public String buildAuthorizeUrl(CompanySsoConfig config, String state) {
        String issuer = config.getBaseUrl();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("OIDC Provider 必须配置 baseUrl（issuer URL）");
        }
        String authorizeEndpoint = issuer + (issuer.endsWith("/") ? "" : "/") + "authorize";
        String scope = config.getScope() != null && !config.getScope().isBlank()
            ? config.getScope()
            : "openid profile email";

        return authorizeEndpoint
            + "?response_type=code"
            + "&client_id=" + URLEncoder.encode(config.getAppId(), StandardCharsets.UTF_8)
            + "&redirect_uri=" + URLEncoder.encode(config.getRedirectUri(), StandardCharsets.UTF_8)
            + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
            + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    @Override
    public SsoToken exchangeCode(String code, CompanySsoConfig config) {
        try {
            String issuer = config.getBaseUrl();
            String tokenEndpoint = issuer + (issuer.endsWith("/") ? "" : "/") + "token";

            Map<String, String> params = new HashMap<>();
            params.put("grant_type", "authorization_code");
            params.put("code", code);
            params.put("redirect_uri", config.getRedirectUri());
            params.put("client_id", config.getAppId());
            params.put("client_secret", config.getAppSecretEnc());

            JsonNode resp = doPostFormJson(tokenEndpoint, params);

            if (resp.has("error")) {
                throw new RuntimeException("OIDC token 失败: " + resp.path("error_description").asText(resp.path("error").asText()));
            }
            return new SsoToken(
                resp.path("access_token").asText(),
                resp.path("refresh_token").asText(null),
                resp.path("expires_in").asLong(3600),
                resp.path("token_type").asText("Bearer")
            );
        } catch (Exception e) {
            throw new RuntimeException("OIDC exchangeCode 异常: " + e.getMessage(), e);
        }
    }

    @Override
    public SsoUserInfo fetchUserInfo(SsoToken token, CompanySsoConfig config) {
        try {
            String issuer = config.getBaseUrl();
            String userinfoEndpoint = issuer + (issuer.endsWith("/") ? "" : "/") + "userinfo";

            okhttp3.Request request = new okhttp3.Request.Builder()
                .url(userinfoEndpoint)
                .get()
                .addHeader("Authorization", "Bearer " + token.getAccessToken())
                .build();

            JsonNode resp;
            try (okhttp3.Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("OIDC userinfo HTTP " + response.code());
                }
                resp = MAPPER.readTree(response.body().string());
            }

            // OIDC 标准 claim：sub / name / email / phone_number
            String sub = resp.path("sub").asText(null);
            String name = resp.path("name").asText(null);
            String email = resp.path("email").asText(null);
            String phone = resp.path("phone_number").asText(null);

            return new SsoUserInfo(sub, sub, name, email, phone, List.of(), null);
        } catch (Exception e) {
            throw new RuntimeException("OIDC fetchUserInfo 异常: " + e.getMessage(), e);
        }
    }
}