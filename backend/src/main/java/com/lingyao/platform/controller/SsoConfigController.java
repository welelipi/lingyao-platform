package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.CompanySsoConfig;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.security.TenantContext;
import com.lingyao.platform.service.SensitiveOtpService;
import com.lingyao.platform.service.SsoConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * SSO 配置管理 API（公司超管后台）
 *
 * - 仅 SUPER_ADMIN 角色 + 本公司 ACTIVE 可访问
 * - 平台超管也可访问
 * - 5 个接口：GET 列表 / GET 详情 / POST 保存 / DELETE 删除 / GET audit-log
 *
 * V2.0.19 新增 — 2026-09-01
 */
@RestController
@RequestMapping("/api/company-admin/sso-config")
public class SsoConfigController {

    @Autowired private SsoConfigService ssoConfigService;
    @Autowired private TenantContext tenantContext;
    // V2.0.77.15：敏感操作 OTP（改/删 SSO 配置）
    @Autowired private SensitiveOtpService sensitiveOtpService;

    // ═══════════════════════ 守卫 ═══════════════════════

    private ApiResponse<?> requireTenantAdmin() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null) return ApiResponse.fail(401, "未登录");
        if (cu.isPlatformAdmin()) return null;
        Long companyId = cu.getCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        if (!tenantContext.isTenantAdmin(cu.getUserId(), companyId)) {
            return ApiResponse.fail(403, "仅公司超管可访问该接口");
        }
        return null;
    }

    private Long getScopedCompanyId() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu.isPlatformAdmin()) {
            Long c = tenantContext.findFirstTenantAdminCompany(cu.getUserId());
            if (c == null) {
                // 平台超管但无公司超管关系，回退到 cu.companyId
                return cu.getCompanyId();
            }
            return c;
        }
        return cu.getCompanyId();
    }

    // ═══════════════════════ 5 个接口 ═══════════════════════

    /**
     * GET /api/company-admin/sso-config — 列出当前公司的所有 SSO 配置（脱敏）
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        ApiResponse<?> guard = requireTenantAdmin();
        if (guard != null) return ApiResponse.fail(guard.getCode(), guard.getMessage());

        Long companyId = getScopedCompanyId();
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        List<Map<String, Object>> configs = ssoConfigService.listConfigs(cu.getUserId(), companyId);
        return ApiResponse.ok(configs);
    }

    /**
     * GET /api/company-admin/sso-config/{provider} — 查某 Provider 配置详情（脱敏）
     */
    @GetMapping("/{provider}")
    public ApiResponse<Map<String, Object>> getOne(@PathVariable String provider) {
        ApiResponse<?> guard = requireTenantAdmin();
        if (guard != null) return ApiResponse.fail(guard.getCode(), guard.getMessage());

        CompanySsoConfig.Provider p;
        try {
            p = CompanySsoConfig.Provider.valueOf(provider.toUpperCase());
        } catch (Exception e) {
            return ApiResponse.fail(400, "不支持的 Provider: " + provider);
        }

        Long companyId = getScopedCompanyId();
        return ssoConfigService.configRepo().findByCompanyIdAndProvider(companyId, p)
            .map(c -> ApiResponse.ok(ssoConfigService.toPublicMap(c)))
            .orElse(ApiResponse.fail(404, "配置不存在"));
    }

    /**
     * POST /api/company-admin/sso-config/{provider} — 保存/更新配置
     */
    @PostMapping("/{provider}")
    public ApiResponse<Map<String, Object>> upsert(@PathVariable String provider,
                                                    @RequestBody Map<String, Object> body) {
        ApiResponse<?> guard = requireTenantAdmin();
        if (guard != null) return ApiResponse.fail(guard.getCode(), guard.getMessage());

        CompanySsoConfig.Provider p;
        try {
            p = CompanySsoConfig.Provider.valueOf(provider.toUpperCase());
        } catch (Exception e) {
            return ApiResponse.fail(400, "不支持的 Provider: " + provider);
        }

        if (p == CompanySsoConfig.Provider.SAML) {
            return ApiResponse.fail(400, "SAML Provider 阶段 2 实施，接口预留中");
        }

        Long companyId = getScopedCompanyId();
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        // V2.0.77.15：敏感操作 OTP（改 SSO 配置 → 影响全员登录）
        String otpCode = body.get("emailOtpCode") == null ? null : String.valueOf(body.get("emailOtpCode"));
        try {
            sensitiveOtpService.verify("CHANGE_SSO_CONFIG", otpCode, cu.getUsername());
            Map<String, Object> result = ssoConfigService.upsertConfig(cu.getUserId(), companyId, p, body);
            return ApiResponse.ok("保存成功", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /**
     * DELETE /api/company-admin/sso-config/{provider} — 删除某 Provider 配置
     */
    @DeleteMapping("/{provider}")
    public ApiResponse<Void> delete(@PathVariable String provider, @RequestBody(required = false) Map<String, String> otpBody) {
        ApiResponse<?> guard = requireTenantAdmin();
        if (guard != null) return ApiResponse.fail(guard.getCode(), guard.getMessage());

        CompanySsoConfig.Provider p;
        try {
            p = CompanySsoConfig.Provider.valueOf(provider.toUpperCase());
        } catch (Exception e) {
            return ApiResponse.fail(400, "不支持的 Provider: " + provider);
        }

        Long companyId = getScopedCompanyId();
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        // V2.0.77.15：删除 SSO 配置 → 同样要 OTP
        String otpCode = otpBody != null ? otpBody.get("emailOtpCode") : null;
        try {
            sensitiveOtpService.verify("DELETE_SSO_CONFIG", otpCode, cu.getUsername());
            ssoConfigService.deleteConfig(cu.getUserId(), companyId, p);
            return ApiResponse.ok("删除成功", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * GET /api/company-admin/sso-config/audit-logs — SSO 登录审计日志
     */
    @GetMapping("/audit-logs")
    public ApiResponse<Map<String, Object>> auditLogs(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        ApiResponse<?> guard = requireTenantAdmin();
        if (guard != null) return ApiResponse.fail(guard.getCode(), guard.getMessage());

        Long companyId = getScopedCompanyId();
        return ApiResponse.ok(ssoConfigService.listAuditLogs(companyId, page, size));
    }

    // ═══════════════════════ V2.0.41 login-link ═══════════════════════

    @Autowired private com.lingyao.platform.repository.CompanyProductRepository companyProductRepo;
    @Autowired private com.lingyao.platform.repository.ProductRepository productRepo;
    @Autowired private com.lingyao.platform.service.AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Value("${lingyao.platform.base-url:http://localhost:8765}")
    private String platformBaseUrl;

    /**
     * POST /api/company-admin/sso-config/{provider}/login-link — 生成员工 SSO 登录链接（V2.0.41）
     *
     * 用途：公司超管配置完 SSO 后，一键生成员工登录入口链接（发到公司群，员工点开直接进 OAuth 授权页）
     *
     * Body（可选）：{ "sub_product": "GEO" } —— 缺省取本公司第一个 ACTIVE License 产品
     * 返回：{ link, company_code, provider, sub_product, oauth_start }
     *
     * 链接格式 = 既有 OAuth 发起端点（SsoCallbackController）：
     *   {platform}/api/sso/{provider}/login?company_code=X&sub_product=Y
     */
    @PostMapping("/{provider}/login-link")
    public ApiResponse<?> loginLink(@PathVariable String provider,
                                     @RequestBody(required = false) Map<String, Object> body) {
        ApiResponse<?> guard = requireTenantAdmin();
        if (guard != null) return guard;

        CompanySsoConfig.Provider p;
        try {
            p = CompanySsoConfig.Provider.valueOf(provider.toUpperCase());
        } catch (Exception e) {
            return ApiResponse.fail(400, "不支持的 Provider: " + provider);
        }

        Long companyId = getScopedCompanyId();
        CurrentUser cu = JwtAuthFilter.getCurrentUser();

        // 1) 配置必须存在且启用
        CompanySsoConfig config = ssoConfigService.configRepo()
                .findByCompanyIdAndProvider(companyId, p)
                .orElse(null);
        if (config == null) {
            return ApiResponse.fail(404, "贵公司尚未配置 " + provider + " SSO，请先配置");
        }
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return ApiResponse.fail(400, provider + " SSO 已禁用，请先启用后再生成登录链接");
        }

        // 2) 目标子产品：显式指定 或 本公司第一个 ACTIVE License 产品
        String subProduct = body == null ? null : (String) body.get("sub_product");
        if (subProduct == null || subProduct.trim().isEmpty()) {
            var licenses = companyProductRepo.findByCompanyIdAndStatus(
                    companyId, com.lingyao.platform.entity.CompanyProduct.Status.ACTIVE);
            if (licenses.isEmpty()) {
                return ApiResponse.fail(400, "贵公司暂无已开通产品，无法生成 SSO 登录链接");
            }
            Long firstProductId = licenses.get(0).getProductId();
            var product = productRepo.findById(firstProductId).orElse(null);
            if (product == null) {
                return ApiResponse.fail(500, "产品数据异常，请联系平台管理员");
            }
            subProduct = product.getCode();
        } else {
            String code = subProduct.trim().toUpperCase();
            if (productRepo.findByCode(code).isEmpty()) {
                return ApiResponse.fail(404, "子产品不存在: " + subProduct);
            }
            subProduct = code;
        }

        // 3) 拼 OAuth 发起链接（与 SsoCallbackController.login 参数一致）
        String companyCode = cu.getCompanyCode();
        String base = platformBaseUrl == null ? "http://localhost:8765" : platformBaseUrl;
        String link = base + "/api/sso/" + provider.toLowerCase()
                + "/login?company_code=" + java.net.URLEncoder.encode(companyCode, java.nio.charset.StandardCharsets.UTF_8)
                + "&sub_product=" + java.net.URLEncoder.encode(subProduct, java.nio.charset.StandardCharsets.UTF_8);

        auditLogService.record("SSO_LOGIN_LINK_GENERATED", "COMPANY",
                String.valueOf(companyId),
                "公司超管 " + cu.getUsername() + " 生成 " + provider + " SSO 登录链接 sub_product=" + subProduct);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("link", link);
        data.put("company_code", companyCode);
        data.put("provider", provider.toUpperCase());
        data.put("sub_product", subProduct);
        data.put("oauth_start", "/api/sso/" + provider.toLowerCase() + "/login");
        return ApiResponse.ok("SSO 登录链接生成成功", data);
    }
}