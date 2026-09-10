package com.lingyao.platform.controller;

import com.lingyao.platform.config.PageableValidator;
import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.admin.BindUserToCompanyRequest;
import com.lingyao.platform.dto.admin.CreateCompanyRequest;
import com.lingyao.platform.dto.admin.CreateCompanyWithAdminRequest;
import com.lingyao.platform.service.RegistrationService;
import com.lingyao.platform.dto.admin.CreateUserRequest;
import com.lingyao.platform.dto.admin.GrantCompanyProductsRequest;
import com.lingyao.platform.dto.admin.GrantProductRequest;
import com.lingyao.platform.dto.admin.UpdateCompanyRequest;
import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.service.AuditLogService;
import com.lingyao.platform.service.SensitiveOtpService;
import com.lingyao.platform.service.TenantAdminService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管后台 API — Bug-18/19/20/21 修复 + P1-B 平台超管能力
 *
 * 权限规则：
 * - 任何端点都要求 platformAdmin=true（私有化部署下"admin"账号天然满足）
 * - 非平台超管 → 返回 403
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired private CompanyRepository companyRepo;
    @Autowired private SysUserRepository userRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private CompanyProductRepository companyProductRepo;
    @Autowired private NotificationChannelRepository channelRepo;
    @Autowired private AuditLogService auditLogService;
    @Autowired private TenantAdminService tenantAdminService;
    @Autowired private RegistrationService registrationService;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private ProductUserGrantRepository grantRepo;
    @Autowired private SubTaskRepository subTaskRepo;
    // V2.0.77.15：敏感操作 OTP（改 GEOM/合同/删公司/重置密码）
    @Autowired private SensitiveOtpService sensitiveOtpService;

    // ──────────────── P1-B 鉴权工具 ────────────────

    /**
     * 仅允许平台超管访问；非超管返回 403。
     */
    private ApiResponse<?> requirePlatformAdmin() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null) return ApiResponse.fail(401, "未登录");
        if (!cu.isPlatformAdmin()) return ApiResponse.fail(403, "仅平台超管可访问该接口");
        return null;
    }

    // ──────────────── P1-B：创建公司 / 用户 / 绑定 / 授权 ────────────────

    @PostMapping("/companies")
    public ApiResponse<Company> createCompany(@Valid @RequestBody CreateCompanyRequest req) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        try {
            return ApiResponse.ok("公司创建成功", tenantAdminService.createCompany(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * V2.0.28 方案A：一步创建公司 + 公司管理员（主人 2026-09-02 拍板 A+B）。
     * admin 区块为 null 时行为同 POST /companies（仅建 PENDING 公司）。
     * 返回 initialPassword 明文仅此一次。
     */
    @PostMapping("/companies/with-admin")
    public ApiResponse<Map<String, Object>> createCompanyWithAdmin(
            @Valid @RequestBody CreateCompanyWithAdminRequest req) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        try {
            return ApiResponse.ok("公司开通成功", tenantAdminService.createCompanyWithAdmin(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * V2.0.28 方案B：报名转开通——报名状态改 CONVERTED（宽于白名单：从 PENDING/CONTACTED/QUALIFIED 均可转入）。
     * 具体开通动作由前端「转开通」按钮引导到组合创建表单完成，本端点只负责关单。
     */
    @PutMapping("/registrations/{id}/convert")
    public ApiResponse<Registration> convertRegistration(@PathVariable Long id) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        try {
            return ApiResponse.ok("报名已转化", registrationService.convertToClosed(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * V2.0.10：更新公司信息（仅 platformAdmin）。
     * code 不可改（唯一标识）；其他字段可改；高危操作前端二次确认。
     *
     * V2.0.77.15：敏感操作 OTP 二次验证（改 GEOM/合同/套餐等高危字段）
     * 请求体额外字段：emailOtpCode（前端弹窗发码后从邮箱复制）
     */
    @PutMapping("/companies/{id}")
    public ApiResponse<Company> updateCompany(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCompanyRequest req) {
        // V2.0.77.15c（SEC-02 修复）：删除了第二个 @RequestBody Map otpBody——
        // Spring 对同一 JSON body 只反序列化一次，otpBody 恒 null 导致 OTP 永远验不过。
        // emailOtpCode 现并入 UpdateCompanyRequest（见 DTO 字段注释）。
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        String otpCode = req.getEmailOtpCode();
        try {
            sensitiveOtpService.verify("COMPANY_CHANGE_GEOM_OR_CONTRACT", otpCode, cu.getUsername());
            return ApiResponse.ok("公司更新成功", tenantAdminService.updateCompany(id, req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * V2.0.77.15：禁用公司 → 强制敏感操作 OTP
     */
    @PostMapping("/companies/{id}/suspend")
    public ApiResponse<Company> suspendCompany(@PathVariable Long id, @RequestBody(required = false) Map<String, String> otpBody) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        String otpCode = otpBody != null ? otpBody.get("emailOtpCode") : null;
        try {
            sensitiveOtpService.verify("COMPANY_SUSPEND", otpCode, cu.getUsername());
            return ApiResponse.ok("公司已禁用", tenantAdminService.suspendCompany(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * V2.0.77.15：激活公司 → 强制敏感操作 OTP
     */
    @PostMapping("/companies/{id}/activate")
    public ApiResponse<Company> activateCompany(@PathVariable Long id, @RequestBody(required = false) Map<String, String> otpBody) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        String otpCode = otpBody != null ? otpBody.get("emailOtpCode") : null;
        try {
            sensitiveOtpService.verify("COMPANY_ACTIVATE", otpCode, cu.getUsername());
            return ApiResponse.ok("公司已激活", tenantAdminService.activateCompany(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * V2.0.77.15：重置用户密码 → 强制敏感操作 OTP（防止内部人员滥用）
     */
    @PostMapping("/users/{userId}/reset-password")
    public ApiResponse<TenantAdminService.ResetPasswordResult> resetUserPassword(@PathVariable Long userId, @RequestBody(required = false) Map<String, String> otpBody) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        String otpCode = otpBody != null ? otpBody.get("emailOtpCode") : null;
        try {
            sensitiveOtpService.verify("ADMIN_RESET_USER_PASSWORD", otpCode, cu.getUsername());
            TenantAdminService.ResetPasswordResult result = tenantAdminService.resetUserPassword(userId);
            return ApiResponse.ok("密码已重置，请将新密码告知用户", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/users")
    public ApiResponse<SysUser> createUser(@Valid @RequestBody CreateUserRequest req) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        try {
            return ApiResponse.ok("用户创建成功", tenantAdminService.createUser(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/companies/bind-user")
    public ApiResponse<CompanyUser> bindUserToCompany(@Valid @RequestBody BindUserToCompanyRequest req) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        try {
            return ApiResponse.ok("绑定成功", tenantAdminService.bindUserToCompany(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/products/grant")
    public ApiResponse<List<ProductUserGrant>> grantProducts(@Valid @RequestBody GrantProductRequest req) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        try {
            return ApiResponse.ok("产品授权成功", tenantAdminService.grantProducts(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * P1-B：为公司开通多个产品（公司级 license）
     */
    @PostMapping("/companies/grant-products")
    public ApiResponse<List<CompanyProduct>> grantCompanyProducts(
            @Valid @RequestBody GrantCompanyProductsRequest req) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        try {
            return ApiResponse.ok("公司产品开通成功",
                    tenantAdminService.grantCompanyProducts(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * P1-B 辅助：列出某用户在各公司的成员关系
     */
    @GetMapping("/users/{userId}/memberships")
    public ApiResponse<List<CompanyUser>> listUserMemberships(@PathVariable Long userId) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        return ApiResponse.ok(companyUserRepo.findByUserId(userId));
    }

    /**
     * P1-B 辅助：列出某用户在某公司的产品授权
     */
    @GetMapping("/users/{userId}/grants")
    public ApiResponse<List<ProductUserGrant>> listUserGrants(
            @PathVariable Long userId,
            @RequestParam Long companyId) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        return ApiResponse.ok(grantRepo.findByCompanyIdAndUserId(companyId, userId));
    }

    /**
     * KPI 辅助：列出所有用户产品授权（分页）
     */
    @GetMapping("/grants")
    public ApiResponse<org.springframework.data.domain.Page<ProductUserGrant>> listAllGrants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        return ApiResponse.ok(grantRepo.findAll(
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by("id").descending())));
    }

    // ──────────────── 原 Bug-18/19/20/21：列表查询 + 通道维护 ────────────────

    @GetMapping("/companies")
    public ApiResponse<List<Company>> listCompanies() {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        return ApiResponse.ok(companyRepo.findAll());
    }

    @GetMapping("/users")
    public ApiResponse<Page<SysUser>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        Pageable pageable = PageableValidator.safeOf(page, size);
        // V2.0.46：只返回「平台超管 + 公司超管（SUPER_ADMIN·ACTIVE）」，
        // 公司普通用户不进平台超管后台 —— 普通用户由公司超管在子平台内管理
        return ApiResponse.ok(userRepo.findPlatformAndTenantAdmins(pageable));
    }

    /**
     * V2.0.46：KPI「用户总数」同口径 —— 只统计平台超管 + 公司超管
     */
    @GetMapping("/users/count")
    public ApiResponse<Long> countAdminUsers() {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        return ApiResponse.ok(userRepo.countPlatformAndTenantAdmins());
    }

    @GetMapping("/products")
    public ApiResponse<List<Map<String, Object>>> listProducts() {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        // V2.0.54：产品目录收敛——管理后台授权下拉/邀请产品选择也只展示 ACTIVE（棱镜/皓元/源策/医呈）
        // V2.0.57：补 deployed 字段（子任务已部署 = 非 COMING_SOON），前端据此把未部署产品置灰
        List<Map<String, Object>> views = new java.util.ArrayList<>();
        for (Product p : productRepo.findByStatusOrderBySortOrderAsc(Product.Status.ACTIVE)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", p.getId());
            v.put("code", p.getCode());
            v.put("name", p.getName());
            v.put("description", p.getDescription());
            v.put("icon", p.getIcon());
            v.put("sortOrder", p.getSortOrder());
            v.put("status", p.getStatus());
            boolean deployed = subTaskRepo.findByProductId(p.getId())
                    .map(st -> st.getStatus() != SubTask.Status.COMING_SOON)
                    .orElse(false);
            v.put("deployed", deployed);
            views.add(v);
        }
        return ApiResponse.ok(views);
    }

    @GetMapping("/company-products")
    public ApiResponse<?> listCompanyProducts(@RequestParam Long companyId) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        return ApiResponse.ok(companyProductRepo.findByCompanyIdAndStatus(
                companyId, CompanyProduct.Status.ACTIVE));
    }

    @GetMapping("/channels")
    public ApiResponse<List<NotificationChannel>> listChannels() {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        return ApiResponse.ok(channelRepo.findAll());
    }

    /**
     * Bug-18：创建推送通道
     */
    @PostMapping("/channels")
    public ApiResponse<NotificationChannel> createChannel(@RequestBody NotificationChannel channel) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        channel.setId(null);
        NotificationChannel saved = channelRepo.save(channel);
        auditLogService.record("CHANNEL_CREATE", "CHANNEL", String.valueOf(saved.getId()),
                "创建推送通道: " + saved.getChannelType() + " - " + saved.getName());
        return ApiResponse.ok("通道创建成功", saved);
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<NotificationChannel> updateChannel(@PathVariable Long id, @RequestBody NotificationChannel body) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        NotificationChannel ch = channelRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("通道不存在: " + id));
        ch.setName(body.getName());
        ch.setWebhookUrl(body.getWebhookUrl());
        ch.setSecret(body.getSecret());
        ch.setStatus(body.getStatus());
        ch.setUpdatedAt(java.time.LocalDateTime.now());
        NotificationChannel saved = channelRepo.save(ch);
        auditLogService.record("CHANNEL_UPDATE", "CHANNEL", String.valueOf(id), "更新推送通道");
        return ApiResponse.ok("通道已更新", saved);
    }

    @DeleteMapping("/channels/{id}")
    public ApiResponse<?> deleteChannel(@PathVariable Long id) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        channelRepo.deleteById(id);
        auditLogService.record("CHANNEL_DELETE", "CHANNEL", String.valueOf(id), "删除推送通道");
        return ApiResponse.ok("通道已删除", null);
    }

    /**
     * Bug-21：审计日志查询
     */
    @GetMapping("/audit-logs")
    public ApiResponse<Page<CompanyAuditLog>> listAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageableValidator.safeOf(page, size);
        return ApiResponse.ok(auditLogService.listForCurrentCompany(page, size));
    }

    // ═══════════════════════ V2.0.61 安全审计可视化 ═══════════════════════

    @Autowired
    private com.lingyao.platform.repository.AuthAuditEventRepository authAuditEventRepo;

    /**
     * 安全事件分页查询（平台超管专用）
     * 筛选：eventType / username（模糊）/ success / companyId，全部可空
     */
    @GetMapping("/security-events")
    public ApiResponse<?> listSecurityEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Long companyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ApiResponse<?> guard = requirePlatformAdmin();
        if (guard != null) return guard;

        AuthAuditEvent.EventType type = null;
        if (eventType != null && !eventType.isBlank()) {
            try {
                type = AuthAuditEvent.EventType.valueOf(eventType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ApiResponse.fail(400, "未知事件类型: " + eventType);
            }
        }
        String uname = (username != null && !username.isBlank()) ? username.trim() : null;
        Long cid = companyId;
        Pageable pageable = PageableValidator.safeOf(page, size);

        Page<AuthAuditEvent> result = authAuditEventRepo.findByFilters(type, uname, success, cid, pageable);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.getContent());
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        data.put("total", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        return ApiResponse.ok(data);
    }

    /**
     * 安全事件近 24h 聚合（KPI 行）：按事件类型计数 + 总数 + 失败数
     */
    @GetMapping("/security-events/summary")
    public ApiResponse<?> securityEventsSummary() {
        ApiResponse<?> guard = requirePlatformAdmin();
        if (guard != null) return guard;

        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusHours(24);
        List<AuthAuditEventRepository.EventTypeCount> counts =
                authAuditEventRepo.countByEventTypeSince(since);

        long total = 0;
        long failed = 0;
        List<Map<String, Object>> byType = new java.util.ArrayList<>();
        for (AuthAuditEventRepository.EventTypeCount c : counts) {
            long n = c.getCount() == null ? 0 : c.getCount();
            total += n;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventType", c.getEventType().name());
            row.put("count", n);
            byType.add(row);
        }
        // 失败数单独统计（跨类型）
        failed = authAuditEventRepo.countByCreatedAtAfterAndSuccessFalse(since);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("since", since);
        data.put("total", total);
        data.put("failed", failed);
        data.put("byType", byType);
        return ApiResponse.ok(data);
    }

    /**
     * Bug-03：推送通道健康监控
     */
    @GetMapping("/push/health")
    public ApiResponse<?> pushHealth() {
        long active = channelRepo.findByStatusOrderBySortOrderAsc(
                NotificationChannel.Status.ACTIVE).size();
        long total = channelRepo.count();
        boolean allInactive = active == 0;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("activeChannels", active);
        res.put("totalChannels", total);
        res.put("healthy", active > 0);
        res.put("alert", allInactive ? "⚠️ 当前无任何激活的推送通道，报名通知将无法送达！" : null);
        res.put("suggestion", allInactive
                ? "请前往 [通知通道管理] 创建一个 WECHAT_WORK 通道并激活"
                : null);
        res.put("channels", channelRepo.findAll());
        if (allInactive) {
            auditLogService.record("PUSH_HEALTH_ALERT", "PUSH", "-", "推送通道全部停用");
        }
        return ApiResponse.ok(res);
    }
}
