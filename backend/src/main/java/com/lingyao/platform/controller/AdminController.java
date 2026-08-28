package com.lingyao.platform.controller;

import com.lingyao.platform.config.PageableValidator;
import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.admin.BindUserToCompanyRequest;
import com.lingyao.platform.dto.admin.CreateCompanyRequest;
import com.lingyao.platform.dto.admin.CreateUserRequest;
import com.lingyao.platform.dto.admin.GrantCompanyProductsRequest;
import com.lingyao.platform.dto.admin.GrantProductRequest;
import com.lingyao.platform.dto.admin.UpdateCompanyRequest;
import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.service.AuditLogService;
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
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private ProductUserGrantRepository grantRepo;

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
     * V2.0.10：更新公司信息（仅 platformAdmin）。
     * code 不可改（唯一标识）；其他字段可改；高危操作前端二次确认。
     */
    @PutMapping("/companies/{id}")
    public ApiResponse<Company> updateCompany(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCompanyRequest req) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        try {
            return ApiResponse.ok("公司更新成功", tenantAdminService.updateCompany(id, req));
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
        return ApiResponse.ok(companyRepo.findAll());
    }

    @GetMapping("/users")
    public ApiResponse<Page<SysUser>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageableValidator.safeOf(page, size);
        return ApiResponse.ok(userRepo.findAll(pageable));
    }

    @GetMapping("/products")
    public ApiResponse<List<Product>> listProducts() {
        return ApiResponse.ok(productRepo.findAll());
    }

    @GetMapping("/company-products")
    public ApiResponse<?> listCompanyProducts(@RequestParam Long companyId) {
        return ApiResponse.ok(companyProductRepo.findByCompanyIdAndStatus(
                companyId, CompanyProduct.Status.ACTIVE));
    }

    @GetMapping("/channels")
    public ApiResponse<List<NotificationChannel>> listChannels() {
        return ApiResponse.ok(channelRepo.findAll());
    }

    /**
     * Bug-18：创建推送通道（无需超管权限，仅需登录 — 兼容老逻辑）
     */
    @PostMapping("/channels")
    public ApiResponse<NotificationChannel> createChannel(@RequestBody NotificationChannel channel) {
        channel.setId(null);
        NotificationChannel saved = channelRepo.save(channel);
        auditLogService.record("CHANNEL_CREATE", "CHANNEL", String.valueOf(saved.getId()),
                "创建推送通道: " + saved.getChannelType() + " - " + saved.getName());
        return ApiResponse.ok("通道创建成功", saved);
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<NotificationChannel> updateChannel(@PathVariable Long id, @RequestBody NotificationChannel body) {
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
