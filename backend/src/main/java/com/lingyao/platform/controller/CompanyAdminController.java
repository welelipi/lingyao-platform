package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.CompanyAuditLog;
import com.lingyao.platform.entity.CompanyUser;
import com.lingyao.platform.entity.ProductUserGrant;
import com.lingyao.platform.repository.CompanyAuditLogRepository;
import com.lingyao.platform.repository.CompanyUserRepository;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.security.TenantContext;
import com.lingyao.platform.service.CompanyAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 公司超管后台 API — 2026-09-01 新增
 *
 * 权限规则：
 * - 必须 SUPER_ADMIN 角色且本公司 ACTIVE 状态
 * - 平台超管也可访问（自动继承其第一个 SUPER_ADMIN 关系所在公司）
 * - 所有操作 scope 到「当前用户作为 SUPER_ADMIN 的第一个公司」
 * - 所有写操作自动记录审计日志
 */
@RestController
@RequestMapping("/api/company-admin")
public class CompanyAdminController {

    @Autowired private CompanyAdminService companyAdminService;
    @Autowired private TenantContext tenantContext;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private CompanyAuditLogRepository companyAuditLogRepo;

    // ═══════════════════════ 守卫 ═══════════════════════

    /**
     * 仅允许公司超管访问（SUPER_ADMIN 角色）
     * 返回 null = 校验通过；返回 ApiResponse.fail = 拦截
     */
    private ApiResponse<?> requireTenantAdmin() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null) return ApiResponse.fail(401, "未登录");
        if (cu.isPlatformAdmin()) return null; // 平台超管放行（不绑定具体公司）
        Long companyId = cu.getCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        if (!tenantContext.isTenantAdmin(cu.getUserId(), companyId)) {
            return ApiResponse.fail(403, "仅公司超管可访问该接口");
        }
        return null;
    }

    /**
     * 获取当前用户作用域的 companyId
     * 平台超管 → 取第一个作为 SUPER_ADMIN 的公司
     * 公司超管 → 取 cu.companyId
     */
    private Long getScopedCompanyId() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null) return null;
        if (cu.isPlatformAdmin()) {
            return tenantContext.findFirstTenantAdminCompany(cu.getUserId());
        }
        return cu.getCompanyId();
    }

    // ═══════════════════════ 1. 概览 ═══════════════════════

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> overview() {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        try {
            Long userId = JwtAuthFilter.getCurrentUser().getUserId();
            return ApiResponse.ok("ok", companyAdminService.getOverview(userId, companyId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ═══════════════════════ 2. 用户管理 ═══════════════════════

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> listUsers() {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        Long userId = JwtAuthFilter.getCurrentUser().getUserId();
        return ApiResponse.ok(companyAdminService.listCompanyUsers(userId, companyId));
    }

    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> inviteUser(@RequestBody Map<String, Object> req) {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        Long userId = JwtAuthFilter.getCurrentUser().getUserId();
        try {
            String username = (String) req.get("username");
            String initialPassword = (String) req.getOrDefault("initialPassword",
                    CompanyAdminService.generateRandomPassword());
            String displayName = (String) req.get("displayName");
            String roleStr = (String) req.getOrDefault("role", "OPERATOR");
            List<Long> productIds = null;
            Object rawProductIds = req.get("productIds");
            if (rawProductIds instanceof List<?> rawList) {
                productIds = rawList.stream()
                    .map(o -> ((Number) o).longValue())
                    .toList();
            }
            Map<String, Object> result = companyAdminService.inviteEmployee(
                    userId, companyId, username, initialPassword, displayName, roleStr, productIds);
            return ApiResponse.ok("员工已加入，初始密码仅显示一次", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PutMapping("/users/{userId}/role")
    public ApiResponse<CompanyUser> changeRole(@PathVariable Long userId, @RequestBody Map<String, Object> req) {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        Long currentUserId = JwtAuthFilter.getCurrentUser().getUserId();
        String newRole = (String) req.get("role");
        try {
            return ApiResponse.ok("角色已修改",
                    companyAdminService.changeUserRole(currentUserId, companyId, userId, newRole));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/users/{userId}/remove")
    public ApiResponse<Void> removeUser(@PathVariable Long userId) {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        Long currentUserId = JwtAuthFilter.getCurrentUser().getUserId();
        try {
            companyAdminService.removeUser(currentUserId, companyId, userId);
            return ApiResponse.ok("员工已移除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ═══════════════════════ 3. 产品授权 ═══════════════════════

    @GetMapping("/products")
    public ApiResponse<List<Map<String, Object>>> listProducts() {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        Long userId = JwtAuthFilter.getCurrentUser().getUserId();
        return ApiResponse.ok(companyAdminService.listCompanyProducts(userId, companyId));
    }

    @PostMapping("/products/{productId}/grant")
    public ApiResponse<ProductUserGrant> grantProduct(@PathVariable Long productId, @RequestBody Map<String, Object> req) {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        Long currentUserId = JwtAuthFilter.getCurrentUser().getUserId();
        Long targetUserId = ((Number) req.get("userId")).longValue();
        try {
            return ApiResponse.ok("授权成功",
                    companyAdminService.grantProductToUser(currentUserId, companyId, productId, targetUserId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/products/{productId}/revoke")
    public ApiResponse<Void> revokeProduct(@PathVariable Long productId, @RequestBody Map<String, Object> req) {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        Long currentUserId = JwtAuthFilter.getCurrentUser().getUserId();
        Long targetUserId = ((Number) req.get("userId")).longValue();
        try {
            companyAdminService.revokeProductFromUser(currentUserId, companyId, productId, targetUserId);
            return ApiResponse.ok("授权已撤销", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ═══════════════════════ 4. 审计日志（本公司） ═══════════════════════

    @GetMapping("/audit-logs")
    public ApiResponse<List<CompanyAuditLog>> auditLogs(@RequestParam(defaultValue = "20") int limit) {
        ApiResponse<?> deny = requireTenantAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Long companyId = getScopedCompanyId();
        if (companyId == null) return ApiResponse.fail(403, "未关联公司");
        // 简化版：取最近的 limit 条（后续可加过滤 + 分页）
        List<CompanyAuditLog> logs = companyAuditLogRepo.findAll().stream()
                .filter(l -> l.getCompanyId() != null && l.getCompanyId().equals(companyId))
                .sorted(Comparator.comparing(CompanyAuditLog::getCreatedAt).reversed())
                .limit(Math.min(limit, 100))
                .toList();
        return ApiResponse.ok(logs);
    }
}