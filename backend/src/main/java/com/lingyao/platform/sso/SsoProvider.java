package com.lingyao.platform.sso;

import com.lingyao.platform.entity.CompanySsoConfig;

import java.util.List;

/**
 * SSO Provider 抽象接口
 *
 * 4 个实现（V2.0.19）：
 * 1) FeishuSsoProvider - 飞书（已踩坑沉淀：client_id ≠ app_id, user_info 是 GET 不是 POST 等）
 * 2) DingtalkSsoProvider - 钉钉
 * 3) WecomSsoProvider - 企业微信（corp_id + agent_id 略复杂）
 * 4) GenericOidcSsoProvider - 通用 OIDC（接 Okta / Auth0 / Azure AD）
 *
 * SAML 2.0 留接口（SamlSsoProvider），阶段 2 实施
 */
public interface SsoProvider {

    /**
     * Provider 名称（对应 CompanySsoConfig.Provider 枚举）
     */
    CompanySsoConfig.Provider getName();

    /**
     * 生成 OAuth 授权跳转 URL（前端 302 到这个 URL）
     *
     * @param config     公司 SSO 配置（含 app_id / redirect_uri / scope）
     * @param state      防 CSRF 随机串（主平台生成，callback 校验）
     * @return           完整跳转 URL
     */
    String buildAuthorizeUrl(CompanySsoConfig config, String state);

    /**
     * OAuth callback: code → access_token
     */
    SsoToken exchangeCode(String code, CompanySsoConfig config);

    /**
     * 用 access_token 拿用户信息（含部门 ID）
     */
    SsoUserInfo fetchUserInfo(SsoToken token, CompanySsoConfig config);
}