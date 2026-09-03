package com.lingyao.platform.service;

import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import com.lingyao.platform.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 公司超管后台业务逻辑 — 2026-09-01 新增
 *
 * 所有方法都以「currentUserId + currentCompanyId」为作用域起点
 * 越权防护：调用方必须先通过 CompanyAdminController.requireTenantAdmin() 守卫
 */
@Service
public class CompanyAdminService {

    @Autowired private TenantContext tenantContext;
    @Autowired private CompanyRepository companyRepo;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private CompanyProductRepository companyProductRepo;
    @Autowired private ProductUserGrantRepository grantRepo;
    @Autowired private SysUserRepository userRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private AuditLogService auditLogService;

    private static final SecureRandom RAND = new SecureRandom();
    private static final char[] PWD_CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%^&*".toCharArray();

    // ═══════════════════════ 1. 概览 ═══════════════════════

    public Map<String, Object> getOverview(Long currentUserId, Long companyId) {
        Company company = companyRepo.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("公司不存在: " + companyId));

        // 已用用户（V2.0.51：只统计超管 SUPER_ADMIN·ACTIVE，与用户管理列表同口径；
        // 普通员工不在主站存在，子平台内员工不计入主站 license 席位展示）
        long usedUsers = companyUserRepo.countByCompanyIdAndRoleAndStatus(
                companyId, CompanyUser.CompanyRole.SUPER_ADMIN, CompanyUser.Status.ACTIVE);

        // 已开通产品
        List<CompanyProduct> companyProducts = companyProductRepo.findByCompanyId(companyId);
        long activeProducts = companyProducts.stream()
                .filter(cp -> cp.getStatus() == CompanyProduct.Status.ACTIVE)
                .count();

        // license 剩余天数（取最早的到期时间）
        long minRemainingDays = Long.MAX_VALUE;
        for (CompanyProduct cp : companyProducts) {
            if (cp.getLicenseEnd() != null) {
                long days = Duration.between(LocalDateTime.now(), cp.getLicenseEnd()).toDays();
                if (days < minRemainingDays) minRemainingDays = days;
            }
        }
        if (minRemainingDays == Long.MAX_VALUE) minRemainingDays = -1;

        // 概览统计
        Map<String, Object> license = new LinkedHashMap<>();
        license.put("maxUsers", company.getMaxUsers() != null ? company.getMaxUsers() : 50);
        license.put("usedUsers", usedUsers);
        license.put("remainingDays", minRemainingDays);
        license.put("activeProducts", activeProducts);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", usedUsers);
        stats.put("activeUsers", usedUsers);
        stats.put("pendingInvitations", 0); // TODO 接入邀请表后填实

        Map<String, Object> companyMap = new LinkedHashMap<>();
        companyMap.put("id", company.getId());
        companyMap.put("name", company.getName());
        companyMap.put("code", company.getCode());
        companyMap.put("status", company.getStatus().name());
        companyMap.put("licensePlan", company.getLicensePlan() != null ? company.getLicensePlan().name() : "STANDARD");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", companyMap);
        result.put("license", license);
        result.put("stats", stats);
        result.put("companyProducts", companyProducts.stream().map(this::toCompanyProductMap).collect(Collectors.toList()));
        return result;
    }

    // ═══════════════════════ 2. 用户管理 ═══════════════════════

    public List<Map<String, Object>> listCompanyUsers(Long currentUserId, Long companyId) {
        // V2.0.51：主站平台只有两种角色——平台超管 + 公司超管。
        // 工作台用户管理只显示超管（SUPER_ADMIN·ACTIVE）；普通员工（OPERATOR/VIEWER）由公司超管
        // 在子平台内管理，不在主站任何列表出现（与 V2.0.46 admin.html 用户管理口径对齐）。
        List<CompanyUser> memberships = companyUserRepo.findByCompanyIdAndRoleInAndStatus(
                companyId,
                List.of(CompanyUser.CompanyRole.SUPER_ADMIN),
                CompanyUser.Status.ACTIVE);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CompanyUser cu : memberships) {
            SysUser user = userRepo.findById(cu.getUserId()).orElse(null);
            if (user == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("companyUserId", cu.getId());
            m.put("userId", user.getId());
            m.put("username", user.getUsername());
            m.put("displayName", user.getDisplayName());
            m.put("email", user.getEmail());
            m.put("role", cu.getRole().name());
            m.put("status", cu.getStatus().name());
            m.put("joinedAt", cu.getCreatedAt());
            m.put("lastLoginAt", user.getLastLoginAt());
            m.put("mustChangePassword", !user.getPasswordChanged());
            // V2.0.51：平台超管标记（前端用于区分「平台超管」vs「公司超管」徽标）
            m.put("platformAdmin", Boolean.TRUE.equals(user.getIsPlatformAdmin()));
            result.add(m);
        }
        // 按角色优先级排序：SUPER_ADMIN > OPERATOR > VIEWER
        result.sort((a, b) -> {
            int ra = roleOrder((String) a.get("role"));
            int rb = roleOrder((String) b.get("role"));
            return Integer.compare(ra, rb);
        });
        return result;
    }

    private int roleOrder(String role) {
        switch (role) {
            case "SUPER_ADMIN": return 0;
            case "OPERATOR": return 1;
            case "VIEWER": return 2;
            default: return 99;
        }
    }

    @Transactional
    public Map<String, Object> inviteEmployee(Long currentUserId, Long companyId,
                                              String username, String initialPassword,
                                              String displayName, String roleStr,
                                              List<Long> productIds) {
        // 1. 创建或查找 SysUser
        Optional<SysUser> existing = userRepo.findByUsername(username);
        SysUser user;
        if (existing.isPresent()) {
            user = existing.get();
        } else {
            user = new SysUser();
            user.setUsername(username);
            user.setDisplayName(displayName != null ? displayName : username);
            user.setStatus(SysUser.UserStatus.ACTIVE);
            user.setIsPlatformAdmin(false);
            user.setPasswordChanged(false); // 首次登录强制改密
            // 密码 BCrypt 加密
            user.setPasswordHash(org.springframework.security.crypto.bcrypt.BCrypt.hashpw(initialPassword,
                org.springframework.security.crypto.bcrypt.BCrypt.gensalt(10)));
            user = userRepo.save(user);
        }

        // 2. 绑定到公司（如已绑定则跳过）
        if (!companyUserRepo.existsByCompanyIdAndUserId(companyId, user.getId())) {
            CompanyUser cu = new CompanyUser();
            cu.setCompanyId(companyId);
            cu.setUserId(user.getId());
            cu.setRole(CompanyUser.CompanyRole.valueOf(roleStr != null ? roleStr : "OPERATOR"));
            cu.setStatus(CompanyUser.Status.ACTIVE);
            companyUserRepo.save(cu);
        }

        // 3. 授权产品（如指定）
        if (productIds != null) {
            for (Long productId : productIds) {
                grantProductToUser(currentUserId, companyId, productId, user.getId());
            }
        }

        // 4. audit log
        auditLogService.record("COMPANY_INVITE_EMPLOYEE", "USER", String.valueOf(user.getId()),
                "公司超管 " + currentUserId + " 邀请员工 " + username + " 加入公司 " + companyId);

        // 5. 返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("initialPassword", initialPassword); // 仅显示一次
        result.put("mustChangePassword", true);
        result.put("companyRole", roleStr != null ? roleStr : "OPERATOR");
        return result;
    }

    @Transactional
    public CompanyUser changeUserRole(Long currentUserId, Long companyId, Long targetUserId, String newRoleStr) {
        // 不能改自己
        if (currentUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("不能修改自己的角色");
        }
        // 检查目标用户属于本公司
        CompanyUser cu = companyUserRepo.findByCompanyIdAndUserId(companyId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("该用户不属于本公司"));
        CompanyUser.CompanyRole newRole = CompanyUser.CompanyRole.valueOf(newRoleStr);

        // 如果是降级（从 SUPER_ADMIN 降下去），检查本公司是否还有其他 SUPER_ADMIN
        if (cu.getRole() == CompanyUser.CompanyRole.SUPER_ADMIN
                && newRole != CompanyUser.CompanyRole.SUPER_ADMIN) {
            List<CompanyUser> remaining = companyUserRepo.findByCompanyIdAndRole(companyId, CompanyUser.CompanyRole.SUPER_ADMIN);
            if (remaining.size() <= 1) {
                throw new IllegalArgumentException("本公司至少需要保留 1 名公司超管，无法降级唯一超管");
            }
        }
        cu.setRole(newRole);
        CompanyUser saved = companyUserRepo.save(cu);
        auditLogService.record("COMPANY_CHANGE_ROLE", "USER", String.valueOf(targetUserId),
                "公司超管 " + currentUserId + " 将用户 " + targetUserId + " 改为 " + newRoleStr);
        return saved;
    }

    @Transactional
    public void removeUser(Long currentUserId, Long companyId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("不能移除自己");
        }
        CompanyUser cu = companyUserRepo.findByCompanyIdAndUserId(companyId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("该用户不属于本公司"));
        // 不能移除唯一 SUPER_ADMIN
        if (cu.getRole() == CompanyUser.CompanyRole.SUPER_ADMIN) {
            List<CompanyUser> remaining = companyUserRepo.findByCompanyIdAndRole(companyId, CompanyUser.CompanyRole.SUPER_ADMIN);
            if (remaining.size() <= 1) {
                throw new IllegalArgumentException("本公司至少需要保留 1 名公司超管，无法移除唯一超管");
            }
        }
        cu.setStatus(CompanyUser.Status.REMOVED);
        companyUserRepo.save(cu);
        // 同时撤销该用户在本公司的所有产品授权
        List<ProductUserGrant> grants = grantRepo.findByCompanyIdAndUserId(companyId, targetUserId);
        for (ProductUserGrant g : grants) {
            g.setStatus(ProductUserGrant.Status.REVOKED);
        }
        auditLogService.record("COMPANY_REMOVE_USER", "USER", String.valueOf(targetUserId),
                "公司超管 " + currentUserId + " 移除用户 " + targetUserId);
    }

    // ═══════════════════════ 3. 产品授权 ═══════════════════════

    public List<Map<String, Object>> listCompanyProducts(Long currentUserId, Long companyId) {
        List<CompanyProduct> companyProducts = companyProductRepo.findByCompanyId(companyId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CompanyProduct cp : companyProducts) {
            Map<String, Object> m = toCompanyProductMap(cp);
            // 计算已授权人数
            long grantedUsers = grantRepo.findByCompanyIdAndProductIdAndUserId(companyId, cp.getProductId(), null) != null ? 0 : 0;
            // 用 existsBy... 更简洁，但 ProductUserGrantRepository 没这方法 → 直接遍历
            // 简化：用 findByCompanyIdAndUserId 然后过滤 productId
            List<ProductUserGrant> allGrants = grantRepo.findByCompanyIdAndUserId(companyId, null) == null
                ? Collections.emptyList()
                : Collections.emptyList(); // 占位，下面用 SQL
            // 实际查询：company_id + product_id 的 grants
            List<ProductUserGrant> grants = grantRepo.findAll().stream()
                .filter(g -> g.getCompanyId().equals(companyId)
                          && g.getProductId().equals(cp.getProductId())
                          && g.getStatus() == ProductUserGrant.Status.ACTIVE)
                .collect(Collectors.toList());
            m.put("grantedUsers", grants.size());
            m.put("remainingSlots", Math.max(0, (cp.getMaxUsers() != null ? cp.getMaxUsers() : 50) - grants.size()));
            // 已授权用户列表
            m.put("grantedUserIds", grants.stream().map(ProductUserGrant::getUserId).collect(Collectors.toList()));
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> toCompanyProductMap(CompanyProduct cp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("companyProductId", cp.getId());
        m.put("productId", cp.getProductId());
        m.put("maxUsers", cp.getMaxUsers() != null ? cp.getMaxUsers() : 50);
        m.put("licenseStart", cp.getLicenseStart());
        m.put("licenseEnd", cp.getLicenseEnd());
        m.put("status", cp.getStatus().name());
        // 产品详情
        productRepo.findById(cp.getProductId()).ifPresent(product -> {
            m.put("code", product.getCode());
            m.put("name", product.getName());
            m.put("icon", product.getIcon());
        });
        return m;
    }

    @Transactional
    public ProductUserGrant grantProductToUser(Long currentUserId, Long companyId, Long productId, Long targetUserId) {
        // 校验产品属于本公司已开通
        CompanyProduct cp = companyProductRepo.findByCompanyIdAndProductIdAndStatus(
                companyId, productId, CompanyProduct.Status.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("该产品未在本公司开通"));

        // 校验 license 未过期
        if (cp.getLicenseEnd() != null && cp.getLicenseEnd().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("license 已过期，无法授权");
        }

        // 校验目标用户属于本公司
        if (!companyUserRepo.existsByCompanyIdAndUserId(companyId, targetUserId)) {
            throw new IllegalArgumentException("目标用户不属于本公司");
        }

        // 校验未超过 maxUsers
        long currentGranted = grantRepo.findAll().stream()
            .filter(g -> g.getCompanyId().equals(companyId)
                      && g.getProductId().equals(productId)
                      && g.getStatus() == ProductUserGrant.Status.ACTIVE)
            .count();
        int maxUsers = cp.getMaxUsers() != null ? cp.getMaxUsers() : 50;
        if (currentGranted >= maxUsers) {
            throw new IllegalArgumentException("已达产品授权上限 " + maxUsers + " 人");
        }

        // 查找已有授权（避免重复）
        Optional<ProductUserGrant> existing = grantRepo.findByCompanyIdAndProductIdAndUserId(companyId, productId, targetUserId);
        ProductUserGrant grant;
        if (existing.isPresent()) {
            grant = existing.get();
            if (grant.getStatus() == ProductUserGrant.Status.ACTIVE) {
                return grant; // 已授权，直接返回
            }
            grant.setStatus(ProductUserGrant.Status.ACTIVE);
            grant = grantRepo.save(grant);
        } else {
            grant = new ProductUserGrant();
            grant.setCompanyId(companyId);
            grant.setProductId(productId);
            grant.setUserId(targetUserId);
            grant.setGrantedBy(currentUserId);
            grant.setStatus(ProductUserGrant.Status.ACTIVE);
            grant = grantRepo.save(grant);
        }

        auditLogService.record("COMPANY_GRANT_PRODUCT", "USER", String.valueOf(targetUserId),
                "公司超管 " + currentUserId + " 给用户 " + targetUserId + " 授权产品 " + productId);
        return grant;
    }

    @Transactional
    public void revokeProductFromUser(Long currentUserId, Long companyId, Long productId, Long targetUserId) {
        ProductUserGrant grant = grantRepo.findByCompanyIdAndProductIdAndUserId(companyId, productId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("未找到该授权记录"));
        grant.setStatus(ProductUserGrant.Status.REVOKED);
        grantRepo.save(grant);
        auditLogService.record("COMPANY_REVOKE_PRODUCT", "USER", String.valueOf(targetUserId),
                "公司超管 " + currentUserId + " 撤销用户 " + targetUserId + " 的产品 " + productId + " 授权");
    }

    // ═══════════════════════ 工具：生成随机初始密码 ═══════════════════════

    public static String generateRandomPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(PWD_CHARS[RAND.nextInt(PWD_CHARS.length)]);
        }
        return sb.toString();
    }
}