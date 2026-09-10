package com.lingyao.platform.controller;

import com.lingyao.platform.entity.CompanySsoConfig;
import com.lingyao.platform.entity.SsoLoginAudit;
import com.lingyao.platform.repository.CompanyRepository;
import com.lingyao.platform.repository.ProductRepository;
import com.lingyao.platform.security.JwtUtil;
import com.lingyao.platform.service.SsoConfigService;
import com.lingyao.platform.sso.SsoProviderFactory;
import com.lingyao.platform.sso.SsoToken;
import com.lingyao.platform.sso.SsoUserInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * SSO OAuth 跳转与回调 Controller
 *
 * 端点：
 * 1) GET /api/sso/{provider}/login — 发起 OAuth 跳转（生成 state + 跳三方授权页）
 * 2) GET /api/sso/{provider}/callback — OAuth callback（拿 code → user_info → JWT → 跳子产品）
 * 3) GET /api/sso/state/{state} — state 校验辅助（缓存 state → company_id 映射）
 *
 * 设计要点：
 * - callback 不需要登录（用户正在登录过程中）
 * - callback URL 必须预登记到三方平台（飞书/钉钉/企微/OIDC）
 * - callback 完成后跳子产品的 SSO callback 端点（带 platform_token fragment）
 *
 * V2.0.19 新增 — 2026-09-01
 */
@Controller
@RequestMapping("/api/sso")
public class SsoCallbackController {

    private static final Logger log = LoggerFactory.getLogger(SsoCallbackController.class);
    private static final SecureRandom RAND = new SecureRandom();

    @Autowired private SsoConfigService ssoConfigService;
    @Autowired private SsoProviderFactory providerFactory;
    @Autowired private CompanyRepository companyRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private JwtUtil jwtUtil;

    @Value("${lingyao.platform.base-url:http://localhost:8765}")
    private String platformBaseUrl;

    /**
     * state → (company_id, provider, sub_product_code, audit_id, created_at) 的临时缓存
     * 实际项目用 Redis；这里用 ConcurrentHashMap + 自动过期
     */
    private final Map<String, StateEntry> stateCache = new java.util.concurrent.ConcurrentHashMap<>();

    // ═══════════════════════ 1. 发起 OAuth 跳转 ═══════════════════════

    /**
     * GET /api/sso/{provider}/login?company_code=xxx&sub_product=GEOM&redirect_to=...
     *
     * 流程：
     * 1) 校验 company_code + sub_product 有效性
     * 2) 查 company_sso_config（公司是否配了此 provider）
     * 3) 生成 state + 缓存到 stateCache
     * 4) 调 provider.buildAuthorizeUrl() → 重定向
     */
    @GetMapping("/{provider}/login")
    public RedirectView login(@PathVariable String provider,
                               @RequestParam String company_code,
                               @RequestParam String sub_product,
                               @RequestParam(required = false) String redirect_to,
                               HttpServletRequest request) {
        try {
            CompanySsoConfig.Provider p = CompanySsoConfig.Provider.valueOf(provider.toUpperCase());

            // 1) 校验 company_code 有效性
            Long companyId = companyRepo.findByCode(company_code)
                .orElseThrow(() -> new IllegalArgumentException("公司代码无效: " + company_code))
                .getId();
            // 2) 校验 sub_product 有效性（V2.0.48：GEOM 等别名归一化为产品表 code）
            String normalizedSubProduct = normalizeSubProductCode(sub_product);
            Long productId = productRepo.findByCode(normalizedSubProduct)
                .orElseThrow(() -> new IllegalArgumentException("子产品代码无效: " + sub_product))
                .getId();
            // 3) 查 SSO 配置
            CompanySsoConfig config = ssoConfigService.configRepo()
                .findByCompanyIdAndProvider(companyId, p)
                .orElseThrow(() -> new IllegalArgumentException("公司未配置 " + provider + " SSO"));
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                throw new IllegalArgumentException("SSO 已禁用: " + provider);
            }

            // 4) 生成 state（32 字节随机 Base64）
            String state = generateState();
            // 5) 缓存 state → 5 分钟过期（V2.0.48：存归一化后的 code，callback 拼回调映射/JWT claim 都用它）
            SsoLoginAudit audit = ssoConfigService.logPending(
                companyId, p, normalizedSubProduct,
                getClientIp(request), request.getHeader("User-Agent")
            );
            stateCache.put(state, new StateEntry(companyId, p, normalizedSubProduct, audit.getId(),
                System.currentTimeMillis() + Duration.ofMinutes(5).toMillis(),
                redirect_to));

            // 6) 生成跳转 URL
            String authorizeUrl = providerFactory.getProvider(p).buildAuthorizeUrl(decryptForCallback(config), state);
            log.info("[SSO] {} 跳转授权 URL: company_id={}, state={}", provider, companyId, state);
            return new RedirectView(authorizeUrl);
        } catch (Exception e) {
            log.error("[SSO] login 失败: {}", e.getMessage(), e);
            return new RedirectView(platformBaseUrl + "/sso-error.html?msg=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        }
    }

    // ═══════════════════════ 2. OAuth callback ═══════════════════════

    /**
     * GET /api/sso/{provider}/callback?code=xxx&state=xxx
     *
     * 流程：
     * 1) 从 stateCache 拿缓存（校验 + 过期）
     * 2) 调 provider.exchangeCode(code) 拿 access_token
     * 3) 调 provider.fetchUserInfo(token) 拿 user_info（含部门）
     * 4) 按 dept_mapping 把飞书部门 ID 映射成凌瑶 BU（可空）
     * 5) 签 JWT（含新 6 个 claim：department_id / employee_id / source / status / bu / org_code）
     * 6) 跳子产品 SSO callback 端点（带 platform_token）
     */
    @GetMapping("/{provider}/callback")
    public RedirectView callback(@PathVariable String provider,
                                  @RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error,
                                  HttpServletRequest request) {
        try {
            // 1) OAuth 失败
            if (error != null) {
                log.warn("[SSO] callback 错误: provider={}, error={}", provider, error);
                return new RedirectView(platformBaseUrl + "/sso-error.html?msg=" + URLEncoder.encode(error, StandardCharsets.UTF_8));
            }
            if (code == null || state == null) {
                throw new IllegalArgumentException("code/state 不能为空");
            }

            StateEntry entry = stateCache.remove(state);
            if (entry == null) {
                throw new IllegalArgumentException("state 无效或已过期");
            }
            if (System.currentTimeMillis() > entry.expiresAt) {
                throw new IllegalArgumentException("state 已过期（5 分钟）");
            }

            CompanySsoConfig.Provider p = entry.provider;
            // 2) 重新加载最新配置（避免配置变更后使用过期 secret）
            CompanySsoConfig config = ssoConfigService.getDecryptedConfig(entry.companyId, p);

            // 3) exchangeCode → token
            SsoToken token = providerFactory.getProvider(p).exchangeCode(code, config);
            // 4) fetchUserInfo → user_info
            SsoUserInfo userInfo = providerFactory.getProvider(p).fetchUserInfo(token, config);

            // 5) dept_mapping（飞书部门 ID → 凌瑶 BU）
            String bu = resolveBu(config, userInfo.getDeptIds());

            // 5.5) V2.0.77.13：读公司 GEOM 套餐（契约 GEOM_quota_contract.md §2）
            String geomPlan = "basic";   // 兜底
            if (entry.companyId != null) {
                geomPlan = companyRepo.findById(entry.companyId)
                        .map(c -> c.getGeomPlan() != null ? c.getGeomPlan() : "basic")
                        .orElse("basic");
            }

            // 6) 签 JWT（含 6 个新 claim + 现有 claim）
            String platformToken = jwtUtil.generateSsoToken(
                userInfo.getEmployeeId(),      // 主平台员工 ID（飞书 open_id / 钉钉 unionid）
                entry.companyId,
                entry.subProductCode,
                p.name(),                       // source
                userInfo.getDeptIds() != null && !userInfo.getDeptIds().isEmpty() ? userInfo.getDeptIds().get(0) : null,
                bu,
                geomPlan                        // V2.0.77.13：GEOM 套餐 basic/pro/flagship
            );

            // 7) 更新 audit_log
            ssoConfigService.logSuccess(entry.auditId, userInfo.getEmployeeId(), userInfo.getProviderUserId(),
                userInfo.getDisplayName(),
                userInfo.getDeptIds() != null && !userInfo.getDeptIds().isEmpty() ? userInfo.getDeptIds().get(0) : null);

            // 8) 跳子产品 SSO callback
            String subCallbackUrl = buildSubProductCallbackUrl(entry.subProductCode, platformToken, entry.redirectTo);
            log.info("[SSO] callback 成功: provider={}, employee_id={}, 跳转到 {}", provider, userInfo.getEmployeeId(), subCallbackUrl);
            return new RedirectView(subCallbackUrl);
        } catch (Exception e) {
            log.error("[SSO] callback 失败: {}", e.getMessage(), e);
            return new RedirectView(platformBaseUrl + "/sso-error.html?msg=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        }
    }

    // ═══════════════════════ 3. 辅助方法 ═══════════════════════

    /**
     * 飞书部门 ID → 凌瑶 BU 映射
     * dept_mapping JSON: {"dept_xxx": "儿科BU", "dept_yyyy": "妇科BU"}
     */
    @SuppressWarnings("unchecked")
    private String resolveBu(CompanySsoConfig config, List<String> deptIds) {
        if (config.getDeptMapping() == null || config.getDeptMapping().isBlank() || deptIds == null || deptIds.isEmpty()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.core.type.TypeReference<Map<String, String>> typeRef =
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {};
            Map<String, String> mapping = mapper.readValue(config.getDeptMapping(), typeRef);
            for (String deptId : deptIds) {
                String bu = mapping.get(deptId);
                if (bu != null) return bu;
            }
            return null;
        } catch (Exception e) {
            log.warn("[SSO] dept_mapping 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把解密后的 config 传给 Provider（注意：这里 config.appSecretEnc 已被改成明文）
     */
    private CompanySsoConfig decryptForCallback(CompanySsoConfig config) {
        // 复用 SsoConfigService.getDecryptedConfig 的逻辑（但不要 save 触发 createdBy 更新）
        return ssoConfigService.getDecryptedConfig(config.getCompanyId(), config.getProvider());
    }

    /**
     * 拼 sub_product code → 传给 productRepo 校验前的别名归一化。
     * 产品表实际 code = GEO/HPD/AIDD/DINFO/PORM（V2.0.48）；
     * 子平台侧习惯叫 GEOM（棱镜仓库名）→ 归一化为 GEO，双值兼容。
     */
    private String normalizeSubProductCode(String code) {
        if (code == null) return null;
        return switch (code.trim().toUpperCase()) {
            case "GEOM" -> "GEO";
            case "MED-PPT", "MEDPPT" -> "MEDPPT";  // 预留（产品表暂无此行）
            default -> code.trim().toUpperCase();
        };
    }

    /**
     * 拼子产品 callback URL（按 sub_product_code 路由到对应子产品前端回调路由）
     * 例：GEO → http://localhost:5182/auth/lingyao/sso-callback#platform_token=xxx
     *
     * V2.0.48 契约对齐（GEOM 窗口交接文档 §四）：
     * - GEOM 前端是 Vue hash 路由，回调路由 = /auth/lingyao/sso-callback#platform_token=<JWT>
     * - 旧路径 /api/auth/sso-callback 是后端端点，前端路由打不开 → 404
     */
    private String buildSubProductCallbackUrl(String subProductCode, String platformToken, String redirectTo) {
        // 子产品「域名 + 前端回调路由」映射（dev 环境 localhost）
        String target = switch (subProductCode.toUpperCase()) {
            case "GEO"  -> "http://localhost:5182/auth/lingyao/sso-callback";   // V2.0.70：5180 被 sandbox broker 占用，GEOM 前端 daemon 实跑 5182（见 scripts/start_geom_frontend_daemon.py）
            case "DINFO" -> "http://localhost:5181/auth/lingyao/sso-callback";  // 同契约预留
            case "HPD", "MPDM" -> "http://localhost:3100/auth/lingyao/sso-callback";
            case "AIDD" -> "http://localhost:13000/auth/lingyao/sso-callback";
            case "PORM" -> "http://localhost:3190/auth/lingyao/sso-callback";
            case "MEDPPT" -> "http://localhost:19881/auth/lingyao/sso-callback";
            default -> platformBaseUrl;
        };
        String fragment = "platform_token=" + URLEncoder.encode(platformToken, StandardCharsets.UTF_8);
        if (redirectTo != null && !redirectTo.isBlank()) {
            fragment += "&redirect_to=" + URLEncoder.encode(redirectTo, StandardCharsets.UTF_8);
        }
        return target + "#" + fragment;
    }

    private String generateState() {
        byte[] bytes = new byte[24];
        RAND.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    /**
     * state 缓存条目
     */
    private static class StateEntry {
        final Long companyId;
        final CompanySsoConfig.Provider provider;
        final String subProductCode;
        final Long auditId;
        final long expiresAt;
        final String redirectTo;
        StateEntry(Long companyId, CompanySsoConfig.Provider provider, String subProductCode,
                   Long auditId, long expiresAt, String redirectTo) {
            this.companyId = companyId;
            this.provider = provider;
            this.subProductCode = subProductCode;
            this.auditId = auditId;
            this.expiresAt = expiresAt;
            this.redirectTo = redirectTo;
        }
    }
}