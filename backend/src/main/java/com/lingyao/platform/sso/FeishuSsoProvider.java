package com.lingyao.platform.sso;

import com.lingyao.platform.entity.CompanySsoConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书 OAuth Provider
 *
 * 已踩坑沉淀（参考 ~/.workbuddy/skills/feishu-oauth-v2/SKILL.md）：
 * 1) token 接口用的是 client_id / client_secret（不是 app_id / app_secret）
 *    但用户配置时习惯说"app_id / app_secret"，所以 entity 字段叫 appId / appSecretEnc，
 *    飞书实现里 clientId = config.getAppId(), clientSecret = decryptSecret(...)
 * 2) user_info 接口是 GET（带 Authorization header），不是 POST
 * 3) access_token 要带 tenant_access_token 前缀（"Bearer t-xxxx"）
 * 4) scope 推荐 "contact:user.id:readonly contact:user.employee_id:readonly contact:department.organize:readonly"
 *
 * 文档：
 * - 授权 URL: https://open.feishu.cn/open-apis/authen/v1/index?app_id=xxx&redirect_uri=xxx&state=xxx&scope=xxx
 * - token:    POST https://open.feishu.cn/open-apis/authen/v2/oauth/token
 * - user_info: GET https://open.feishu.cn/open-apis/authen/v1/user_info
 */
@Component
public class FeishuSsoProvider extends AbstractSsoProvider {

    @Override
    public CompanySsoConfig.Provider getName() {
        return CompanySsoConfig.Provider.FEISHU;
    }

    @Override
    public String buildAuthorizeUrl(CompanySsoConfig config, String state) {
        String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
            ? config.getBaseUrl()
            : "https://open.feishu.cn";
        String scope = config.getScope() != null && !config.getScope().isBlank()
            ? config.getScope()
            : "contact:user.id:readonly contact:user.employee_id:readonly contact:department.organize:readonly";

        return baseUrl + "/open-apis/authen/v1/index"
            + "?app_id=" + URLEncoder.encode(config.getAppId(), StandardCharsets.UTF_8)
            + "&redirect_uri=" + URLEncoder.encode(config.getRedirectUri(), StandardCharsets.UTF_8)
            + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
            + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
    }

    @Override
    public SsoToken exchangeCode(String code, CompanySsoConfig config) {
        try {
            String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl()
                : "https://open.feishu.cn";
            // 注意：app_secret 用解密后的明文（业务层 SsoConfigService 已解密）
            Map<String, String> params = new HashMap<>();
            params.put("client_id", config.getAppId());
            params.put("client_secret", config.getAppSecretEnc());   // 业务层保证传入的是明文
            params.put("code", code);
            params.put("grant_type", "authorization_code");
            params.put("redirect_uri", config.getRedirectUri());

            JsonNode resp = doPostFormJson(baseUrl + "/open-apis/authen/v2/oauth/token", params);

            if (resp.has("code") && resp.get("code").asInt() != 0) {
                throw new RuntimeException("飞书 token 失败: " + resp.path("msg").asText());
            }
            JsonNode data = resp.path("data");
            return new SsoToken(
                data.path("access_token").asText(),
                data.path("refresh_token").asText(null),
                data.path("expires_in").asLong(7200),
                data.path("token_type").asText("Bearer")
            );
        } catch (Exception e) {
            throw new RuntimeException("飞书 exchangeCode 异常: " + e.getMessage(), e);
        }
    }

    @Override
    public SsoUserInfo fetchUserInfo(SsoToken token, CompanySsoConfig config) {
        try {
            String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl()
                : "https://open.feishu.cn";
            // 注意：飞书 user_info 是 GET（不是 POST），Authorization header 加 access_token
            okhttp3.Request request = new okhttp3.Request.Builder()
                .url(baseUrl + "/open-apis/authen/v1/user_info")
                .get()
                .addHeader("Authorization", "Bearer " + token.getAccessToken())
                .build();

            JsonNode resp;
            try (okhttp3.Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("飞书 user_info HTTP " + response.code());
                }
                resp = MAPPER.readTree(response.body().string());
            }

            if (resp.has("code") && resp.get("code").asInt() != 0) {
                throw new RuntimeException("飞书 user_info 失败: " + resp.path("msg").asText());
            }
            JsonNode data = resp.path("data");

            // 飞书返回：employee_id / open_id / name / email / mobile / avatar_url
            List<String> deptIds = Arrays.asList();   // 飞书 user_info 不直接返回部门，需另调 contact/department 列表
            return new SsoUserInfo(
                data.path("employee_id").asText(null),
                data.path("open_id").asText(null),
                data.path("name").asText(null),
                data.path("email").asText(null),
                data.path("mobile").asText(null),
                deptIds,
                data.path("avatar_url").asText(null)
            );
        } catch (Exception e) {
            throw new RuntimeException("飞书 fetchUserInfo 异常: " + e.getMessage(), e);
        }
    }
}