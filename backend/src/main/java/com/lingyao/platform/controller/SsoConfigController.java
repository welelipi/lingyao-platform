package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.CompanySsoConfig;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.security.TenantContext;
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
        try {
            Map<String, Object> result = ssoConfigService.upsertConfig(cu.getUserId(), companyId, p, body);
            return ApiResponse.ok("保存成功", result);
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /**
     * DELETE /api/company-admin/sso-config/{provider} — 删除某 Provider 配置
     */
    @DeleteMapping("/{provider}")
    public ApiResponse<Void> delete(@PathVariable String provider) {
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
        ssoConfigService.deleteConfig(cu.getUserId(), companyId, p);
        return ApiResponse.ok("删除成功", null);
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
}