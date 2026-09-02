package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.Product;
import com.lingyao.platform.repository.CompanyRepository;
import com.lingyao.platform.repository.ProductRepository;
import com.lingyao.platform.repository.ProductUserGrantRepository;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.security.JwtUtil;
import com.lingyao.platform.service.AuditLogService;
import com.lingyao.platform.service.CompanyAdminService;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2.0.39 — 服务间授权同步接口（审批下放子平台的配套契约）
 *
 * 背景（主人 2026-09-02 14:44 拍板）：
 * - 全部审批（含产品开通申请）归各子平台自管，主站不再受理申请
 * - 子平台审批通过后，主站 product_user_grant 需同步激活，否则主站 /enter 仍 403
 * - 方案 A：子平台服务端调本接口写回授权（服务间鉴权，用户浏览器不可直接调用）
 *
 * 鉴权：Authorization: Bearer <service JWT>
 *   - 子平台用共享的 LINGYAO_JWT_SECRET 签发（见 JwtUtil.generateServiceToken 契约）
 *   - audience 必须为 lingyao-service，5 分钟有效
 *   - 注意：不能用用户 token 冒充（audience 校验拦截）
 *
 * 授权写回复用 CompanyAdminService.grantProductToUser / revokeProductFromUser：
 * 自动继承 license 校验、maxUsers 上限、幂等、审计全套逻辑。
 */
@RestController
@RequestMapping("/api/internal/grants")
public class InternalGrantsController {

    @Autowired private JwtUtil jwtUtil;
    @Autowired private CompanyAdminService companyAdminService;
    @Autowired private ProductRepository productRepo;
    @Autowired private CompanyRepository companyRepo;
    @Autowired private ProductUserGrantRepository grantRepo;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.lingyao.platform.repository.SysUserRepository userRepo;
    @Autowired private com.lingyao.platform.repository.CompanyUserRepository companyUserRepo;

    /** 服务间调用守卫：验 service JWT；失败返回 fail 响应，成功把调用方标识写入 caller */

    private ApiResponse<?> requireService(String authHeader, Out<String> caller) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ApiResponse.fail(401, "缺少服务间鉴权 token");
        }
        try {
            caller.value = jwtUtil.parseServiceToken(authHeader.substring(7).trim());
            return null;
        } catch (JwtException e) {
            return ApiResponse.fail(401, "服务间鉴权失败: " + e.getMessage());
        }
    }

    /** 简易 out 参数容器 */
    private static class Out<T> { T value; }

    /**
     * POST /api/internal/grants/sync — 子平台审批通过 → 主站激活产品授权
     *
     * Body: { companyCode, productCode, username, approver? }
     *   companyCode: 主站公司 code（如 A-药企；子平台从 SSO claim ccode / identityAssertion tenant.code 获得）
     *   productCode: 主站产品 code（GEO/HPD/AIDD/DINFO/PORM）
     *   username:    主站用户名（子平台从 SSO claim username 获得）
     *   approver:    子平台侧审批人显示名（选填，进审计）
     */
    @PostMapping("/sync")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<?> sync(@RequestHeader(value = "Authorization", required = false) String authHeader,
                               @RequestBody Map<String, Object> body) {
        Out<String> caller = new Out<>();
        ApiResponse<?> deny = requireService(authHeader, caller);
        if (deny != null) return deny;

        String companyCode = str(body.get("companyCode"));
        String productCode = str(body.get("productCode"));
        String username = str(body.get("username"));
        String approver = str(body.get("approver"));
        if (companyCode == null || productCode == null || username == null) {
            return ApiResponse.fail(400, "缺少必填字段：companyCode / productCode / username");
        }

        var company = companyRepo.findByCode(companyCode).orElse(null);
        if (company == null) return ApiResponse.fail(404, "公司不存在: " + companyCode);
        Product product = productRepo.findByCode(productCode.toUpperCase()).orElse(null);
        if (product == null) return ApiResponse.fail(404, "产品不存在: " + productCode);

        // 找主站用户：按 username 精确（子平台 SSO 登录必然携带主站 username claim）
        var userOpt = userRepo.findByUsername(username);
        if (userOpt.isEmpty()) return ApiResponse.fail(404, "用户不存在: " + username);
        Long userId = userOpt.get().getId();
        Long companyId = company.getId();

        // 用户必须属于该公司
        if (!companyUserRepo.existsByCompanyIdAndUserId(companyId, userId)) {
            return ApiResponse.fail(403, "用户 " + username + " 不属于公司 " + companyCode);
        }

        // 幂等：已授权直接返回成功
        if (grantRepo.existsByCompanyIdAndProductIdAndUserIdAndStatus(
                companyId, product.getId(), userId,
                com.lingyao.platform.entity.ProductUserGrant.Status.ACTIVE)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ALREADY_ACTIVE");
            data.put("duplicate", true);
            return ApiResponse.ok("授权已存在，无需重复开通", data);
        }

        // 复用公司超管授权链路（license 校验 + maxUsers 上限 + 幂等 + 审计）
        // grantedBy 传 0 表示系统服务间调用（NOT NULL 约束）
        try {
            companyAdminService.grantProductToUser(0L, companyId, product.getId(), userId);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }

        auditLogService.record("INTERNAL_GRANT_SYNC", "USER", String.valueOf(userId),
                "子平台[" + caller.value + "]审批通过 → 同步授权 product=" + product.getCode()
                        + " user=" + username + (approver != null ? " approver=" + approver : ""));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ACTIVATED");
        data.put("companyCode", companyCode);
        data.put("productCode", product.getCode());
        data.put("username", username);
        return ApiResponse.ok("授权已同步激活", data);
    }

    /**
     * POST /api/internal/grants/revoke — 子平台撤销授权（V2.0.39 配套，幂等）
     *
     * Body: { companyCode, productCode, username, reason? }
     */
    @PostMapping("/revoke")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<?> revoke(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                 @RequestBody Map<String, Object> body) {
        Out<String> caller = new Out<>();
        ApiResponse<?> deny = requireService(authHeader, caller);
        if (deny != null) return deny;

        String companyCode = str(body.get("companyCode"));
        String productCode = str(body.get("productCode"));
        String username = str(body.get("username"));
        if (companyCode == null || productCode == null || username == null) {
            return ApiResponse.fail(400, "缺少必填字段：companyCode / productCode / username");
        }

        var company = companyRepo.findByCode(companyCode).orElse(null);
        if (company == null) return ApiResponse.fail(404, "公司不存在: " + companyCode);
        Product product = productRepo.findByCode(productCode.toUpperCase()).orElse(null);
        if (product == null) return ApiResponse.fail(404, "产品不存在: " + productCode);
        var userOpt = userRepo.findByUsername(username);
        if (userOpt.isEmpty()) return ApiResponse.fail(404, "用户不存在: " + username);

        var grant = grantRepo.findByCompanyIdAndProductIdAndUserId(
                company.getId(), product.getId(), userOpt.get().getId());
        if (grant.isEmpty() || grant.get().getStatus() == com.lingyao.platform.entity.ProductUserGrant.Status.REVOKED) {
            return ApiResponse.ok("授权不存在或已撤销（幂等）", Map.of("status", "IDEMPOTENT_OK"));
        }

        companyAdminService.revokeProductFromUser(0L, company.getId(), product.getId(), userOpt.get().getId());
        auditLogService.record("INTERNAL_GRANT_REVOKE", "USER", String.valueOf(userOpt.get().getId()),
                "子平台[" + caller.value + "]撤销授权 product=" + product.getCode() + " user=" + username);

        return ApiResponse.ok("授权已同步撤销", Map.of("status", "REVOKED"));
    }

    private String str(Object o) {
        return o == null ? null : o.toString().trim();
    }
}
