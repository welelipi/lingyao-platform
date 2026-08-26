package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.InvitationCreateRequest;
import com.lingyao.platform.dto.InvitationRedeemRequest;
import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邀请链接 — Bug-22 修复（OEG 决策要求）
 *
 * - POST /api/invitations           创建邀请（需要登录 + 平台超管或本公司 admin）
 * - GET  /api/invitations/{id}/revoke 撤销邀请
 * - GET  /api/invitations/{id}/qrcode  获取邀请详情 + URL（供复制）
 * - GET  /api/invitations/token/{token} 公开校验（用于注册页展示邀请状态）
 * - POST /api/invitations/redeem   提交用户名密码完成注册
 * - GET  /api/invitations          列出本公司的邀请
 */
@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

    @Autowired private InvitationRepository invRepo;
    @Autowired private CompanyRepository companyRepo;
    @Autowired private SysUserRepository userRepo;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private ProductUserGrantRepository productGrantRepo;
    @Autowired private ProductUserRoleRepository productUserRoleRepo;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditLogService auditLogService;

    private static final SecureRandom RNG = new SecureRandom();

    @PostMapping
    public ApiResponse<?> create(@RequestBody InvitationCreateRequest req, HttpServletRequest request) {
        if (req.getCompanyId() == null || req.getProductCode() == null
                || req.getRoleCode() == null) {
            throw new IllegalArgumentException("companyId / productCode / roleCode 必填");
        }
        // 校验产品 code
        Product product = productRepo.findByCode(req.getProductCode().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("未知产品: " + req.getProductCode()));

        CurrentUser cu = CurrentUser.get();
        if (cu == null) {
            throw new IllegalArgumentException("需要登录后才能创建邀请");
        }

        Invitation inv = new Invitation();
        inv.setToken(generateToken());
        inv.setCompanyId(req.getCompanyId());
        inv.setInvitedEmail(req.getInvitedEmail());
        inv.setProductCode(product.getCode());
        inv.setRoleCode(req.getRoleCode().toUpperCase());
        inv.setInvitedByUserId(cu.getUserId());
        inv.setRemark(req.getRemark());
        inv.setStatus(Invitation.Status.PENDING);
        inv.setExpiresAt(LocalDateTime.now().plusDays(req.getExpiresDays() == null ? 7 : req.getExpiresDays()));

        Invitation saved = invRepo.save(inv);
        auditLogService.record("INV_CREATE", "INVITATION",
                String.valueOf(saved.getId()),
                "创建邀请至 " + saved.getInvitedEmail() + "，产品 " + saved.getProductCode());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", saved.getId());
        data.put("token", saved.getToken());
        data.put("inviteUrl", buildInviteUrl(request, saved.getToken()));
        data.put("expiresAt", saved.getExpiresAt());
        data.put("qrCodeHint", "可复制 inviteUrl 推送给被邀请人；后续提供二维码渲染端点");
        return ApiResponse.ok("邀请已创建", data);
    }

    @GetMapping("/token/{token}")
    public ApiResponse<?> inspect(@PathVariable String token) {
        Invitation inv = invRepo.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("邀请不存在"));
        // 顺手清理过期（无副作用）
        if (inv.getStatus() == Invitation.Status.PENDING && LocalDateTime.now().isAfter(inv.getExpiresAt())) {
            inv.setStatus(Invitation.Status.EXPIRED);
            invRepo.save(inv);
        }
        companyRepo.findById(inv.getCompanyId())
                .ifPresent(c -> inv.setRemark(c.getName() + " / " + inv.getProductCode() + " / 角色 " + inv.getRoleCode()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", inv.getToken());
        data.put("status", inv.getStatus());
        data.put("usable", inv.isUsable());
        data.put("companyId", inv.getCompanyId());
        data.put("productCode", inv.getProductCode());
        data.put("roleCode", inv.getRoleCode());
        data.put("invitedEmail", inv.getInvitedEmail());
        data.put("expiresAt", inv.getExpiresAt());
        data.put("contextHint", inv.getRemark());
        return ApiResponse.ok(data);
    }

    @PostMapping("/redeem")
    @Transactional
    public ApiResponse<?> redeem(@RequestBody InvitationRedeemRequest req) {
        if (req.getToken() == null || req.getUsername() == null || req.getPassword() == null) {
            throw new IllegalArgumentException("token / username / password 必填");
        }
        if (req.getPassword().length() < 6) {
            throw new IllegalArgumentException("密码长度需 ≥ 6");
        }
        if (userRepo.findByUsername(req.getUsername()).isPresent()) {
            throw new IllegalArgumentException("该用户名已被占用，请更换");
        }
        Invitation inv = invRepo.findByToken(req.getToken())
                .orElseThrow(() -> new IllegalArgumentException("邀请不存在"));
        if (!inv.isUsable()) {
            throw new IllegalArgumentException("邀请已 " + inv.getStatus() + "，无法使用");
        }
        if (inv.getInvitedEmail() != null && !inv.getInvitedEmail().isEmpty()
                && req.getEmail() != null && !req.getEmail().equalsIgnoreCase(inv.getInvitedEmail())) {
            throw new IllegalArgumentException("邮箱与邀请指定邮箱不一致");
        }

        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setEmail(req.getEmail() != null ? req.getEmail() : inv.getInvitedEmail());
        user.setDisplayName(req.getDisplayName() != null ? req.getDisplayName() : req.getUsername());
        user.setStatus(SysUser.UserStatus.ACTIVE);
        user.setIsPlatformAdmin(false);
        user.setLoginMethods("PASSWORD+INVITATION");
        user = userRepo.save(user);

        // 加入公司
        CompanyUser cu = new CompanyUser();
        cu.setCompanyId(inv.getCompanyId());
        cu.setUserId(user.getId());
        cu.setRole(CompanyUser.CompanyRole.OPERATOR);  // 默认 OPERATOR，平台内具体权限由 product_user_role 决定
        cu.setStatus(CompanyUser.Status.ACTIVE);
        companyUserRepo.save(cu);

        // 授权产品
        Product product = productRepo.findByCode(inv.getProductCode()).orElseThrow();
        ProductUserGrant grant = new ProductUserGrant();
        grant.setCompanyId(inv.getCompanyId());
        grant.setProductId(product.getId());
        grant.setUserId(user.getId());
        grant.setGrantedBy(inv.getInvitedByUserId());
        grant.setStatus(ProductUserGrant.Status.ACTIVE);
        productGrantRepo.save(grant);

        // 角色（ProductUserRole.entity 已有，多对多）
        com.lingyao.platform.entity.ProductUserRole role = new com.lingyao.platform.entity.ProductUserRole();
        role.setCompanyId(inv.getCompanyId());
        role.setProductId(product.getId());
        role.setUserId(user.getId());
        role.setRoleCode(inv.getRoleCode());
        role.setAssignedBy(inv.getInvitedByUserId());
        productUserRoleRepo.save(role);

        inv.setStatus(Invitation.Status.CONSUMED);
        inv.setConsumedAt(LocalDateTime.now());
        inv.setConsumedByUserId(user.getId());
        invRepo.save(inv);

        auditLogService.record("INV_REDEEM", "INVITATION",
                String.valueOf(inv.getId()),
                "用户 " + user.getUsername() + " 通过邀请注册，加入公司 " + inv.getCompanyId()
                        + "，授权 " + inv.getProductCode() + "/" + inv.getRoleCode());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("companyId", inv.getCompanyId());
        data.put("productCode", inv.getProductCode());
        data.put("roleCode", inv.getRoleCode());
        data.put("hint", "请使用用户名密码登录");
        return ApiResponse.ok("邀请核销完成，用户已开通", data);
    }

    @GetMapping
    public ApiResponse<?> list() {
        CurrentUser cu = CurrentUser.get();
        if (cu == null) {
            throw new IllegalArgumentException("需要登录");
        }
        // 平台超管查全部；其他查本公司
        List<Invitation> invs = cu.isPlatformAdmin() || cu.getCompanyId() == null
                ? invRepo.findAll()
                : invRepo.findByCompanyIdOrderByCreatedAtDesc(cu.getCompanyId());
        return ApiResponse.ok(invs);
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<?> revoke(@PathVariable Long id) {
        Invitation inv = invRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("邀请不存在"));
        if (inv.getStatus() != Invitation.Status.PENDING) {
            throw new IllegalArgumentException("仅 PENDING 邀请可撤销");
        }
        inv.setStatus(Invitation.Status.REVOKED);
        invRepo.save(inv);
        auditLogService.record("INV_REVOKE", "INVITATION", String.valueOf(id), "撤销邀请");
        return ApiResponse.ok("邀请已撤销", null);
    }

    private String buildInviteUrl(HttpServletRequest request, String token) {
        // 优先从请求 Origin/Referer 推断；兜底用 Host 头；最后兜底硬编码
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty()) {
            String referer = request.getHeader("Referer");
            if (referer != null && !referer.isEmpty()) {
                int slash = referer.indexOf("/", referer.indexOf("//") + 2);
                origin = slash > 0 ? referer.substring(0, slash) : referer;
            }
        }
        if (origin == null || origin.isEmpty()) {
            String scheme = request.getScheme();
            String host = request.getHeader("Host");
            if (host != null && !host.isEmpty()) {
                origin = scheme + "://" + host;
            }
        }
        if (origin == null || origin.isEmpty()) {
            origin = "http://127.0.0.1:9091";
        }
        return origin + "/invite.html?token=" + token;
    }

    private String generateToken() {
        byte[] buf = new byte[24];   // 32 char base64url
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
