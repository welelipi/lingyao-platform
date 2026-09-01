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
 * 企业微信 OAuth Provider
 *
 * 文档：
 * - 授权 URL: https://open.weixin.qq.com/connect/oauth2/authorize?appid=corp_id&redirect_uri=xxx&response_type=code&scope=snsapi_privateinfo&state=xxx&agentid=xxx#wechat_redirect
 * - token:    GET https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo?access_token=xxx&code=xxx
 *   ⚠️ 企微特殊：用 code + access_token(企业 access_token) 直接调 user_info，不走标准 oauth2 token 流程
 *
 * 配置 appId = corp_id，extra_config 存 agent_id（JSON {"agent_id": "xxx"}）
 */
@Component
public class WecomSsoProvider extends AbstractSsoProvider {

    @Override
    public CompanySsoConfig.Provider getName() {
        return CompanySsoConfig.Provider.WECOM;
    }

    @Override
    public String buildAuthorizeUrl(CompanySsoConfig config, String state) {
        String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
            ? config.getBaseUrl()
            : "https://open.weixin.qq.com";
        String agentId = extractAgentId(config);

        return baseUrl + "/connect/oauth2/authorize"
            + "?appid=" + URLEncoder.encode(config.getAppId(), StandardCharsets.UTF_8)
            + "&redirect_uri=" + URLEncoder.encode(config.getRedirectUri(), StandardCharsets.UTF_8)
            + "&response_type=code"
            + "&scope=snsapi_privateinfo"
            + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
            + (agentId != null ? "&agentid=" + URLEncoder.encode(agentId, StandardCharsets.UTF_8) : "")
            + "#wechat_redirect";
    }

    @Override
    public SsoToken exchangeCode(String code, CompanySsoConfig config) {
        // 企微不走标准 token 流程，access_token 是企业级的（用 corp_id + corp_secret 拿）
        // 这里返回的 token 实际上是"企业 access_token"，后续 fetchUserInfo 用它 + code 调 auth/getuserinfo
        try {
            String apiBase = "https://qyapi.weixin.qq.com";
            JsonNode resp = doGetJson(apiBase + "/cgi-bin/gettoken"
                + "?corpid=" + URLEncoder.encode(config.getAppId(), StandardCharsets.UTF_8)
                + "&corpsecret=" + URLEncoder.encode(config.getAppSecretEnc(), StandardCharsets.UTF_8));

            if (resp.has("errcode") && resp.get("errcode").asInt() != 0) {
                throw new RuntimeException("企微 gettoken 失败: " + resp.path("errmsg").asText());
            }
            String accessToken = resp.path("access_token").asText();
            // 把 code 临时塞到 token 对象里（企微流程特殊）
            return new SsoToken(accessToken, code, 7200L, "Bearer");
        } catch (Exception e) {
            throw new RuntimeException("企微 exchangeCode 异常: " + e.getMessage(), e);
        }
    }

    @Override
    public SsoUserInfo fetchUserInfo(SsoToken token, CompanySsoConfig config) {
        try {
            String apiBase = "https://qyapi.weixin.qq.com";
            // 企微 user_info：access_token + code
            JsonNode resp = doGetJson(apiBase + "/cgi-bin/auth/getuserinfo"
                + "?access_token=" + URLEncoder.encode(token.getAccessToken(), StandardCharsets.UTF_8)
                + "&code=" + URLEncoder.encode(token.getRefreshToken(), StandardCharsets.UTF_8));

            if (resp.has("errcode") && resp.get("errcode").asInt() != 0) {
                throw new RuntimeException("企微 user_info 失败: " + resp.path("errmsg").asText());
            }

            String userId = resp.path("userid").asText(null);
            String userTicket = resp.path("user_ticket").asText(null);

            // 企微 user_info 只返回 userid/openid，需要 user_ticket 调 auth/getuserdetail 拿详细信息
            String displayName = null;
            String mobile = null;
            String email = null;
            String avatar = null;
            List<String> deptIds = List.of();

            if (userTicket != null && !userTicket.isEmpty()) {
                try {
                    JsonNode detail = doGetJson(apiBase + "/cgi-bin/auth/getuserdetail"
                        + "?access_token=" + URLEncoder.encode(token.getAccessToken(), StandardCharsets.UTF_8));
                    if (detail.has("errcode") && detail.get("errcode").asInt() == 0) {
                        JsonNode userInfo = detail.path("user_info");
                        displayName = userInfo.path("name").asText(null);
                        mobile = userInfo.path("mobile").asText(null);
                        email = userInfo.path("email").asText(null);
                        avatar = userInfo.path("avatar").asText(null);
                    }
                } catch (Exception ignored) {
                    // 非关键失败，主流程继续
                }
            }

            return new SsoUserInfo(userId, userId, displayName, email, mobile, deptIds, avatar);
        } catch (Exception e) {
            throw new RuntimeException("企微 fetchUserInfo 异常: " + e.getMessage(), e);
        }
    }

    private String extractAgentId(CompanySsoConfig config) {
        if (config.getExtraConfig() == null || config.getExtraConfig().isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(config.getExtraConfig());
            return node.path("agent_id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}