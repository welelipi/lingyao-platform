package com.lingyao.platform.service;

import com.lingyao.platform.dto.LoginRequest;
import com.lingyao.platform.dto.LoginResponse;
import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 认证服务 — Bug-09 修复登录防爆破
 */
@Service
public class AuthService {

    @Autowired private SysUserRepository userRepo;
    @Autowired private CompanyRepository companyRepo;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private CompanyProductRepository companyProductRepo;
    @Autowired private ProductUserGrantRepository grantRepo;
    @Autowired private ProductRoleRepository roleRepo;
    @Autowired private ProductUserRoleRepository userRoleRepo;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private LoginAttemptService attemptService;
    @Autowired private AuditLogService auditLogService;

    public LoginResponse login(LoginRequest req, String ip) {
        // Bug-09：登录前先检查是否锁定
        if (attemptService.isLocked(req.getUsername())) {
            String msg = attemptService.getLockMessage(req.getUsername());
            throw new IllegalArgumentException(msg != null ? msg : "账号已锁定，请稍后重试");
        }

        SysUser user = userRepo.findByUsername(req.getUsername()).orElse(null);
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            attemptService.recordFailure(req.getUsername());
            auditLogService.record("LOGIN_FAIL", "USER", String.valueOf(user == null ? -1 : user.getId()), "登录失败: " + req.getUsername());
            // Bug-09：剩余尝试次数提示
            int remaining = 5 - (attemptService.isLocked(req.getUsername()) ? 5 : 0);
            throw new IllegalArgumentException("用户名或密码错误" + (remaining < 5 ? "，剩余尝试次数: " + remaining : ""));
        }

        if (user.getStatus() != SysUser.UserStatus.ACTIVE) {
            throw new IllegalArgumentException("账号已停用");
        }

        // 成功清零
        attemptService.recordSuccess(req.getUsername());

        // 解析用户的多公司关系
        List<CompanyUser> memberships = companyUserRepo.findByUserId(user.getId()).stream()
                .filter(cu -> cu.getStatus() == CompanyUser.Status.ACTIVE)
                .toList();
        Long currentCompanyId = memberships.isEmpty() ? null : memberships.get(0).getCompanyId();

        // Phase 2 准备：从 Company 和 CompanyUser 加载 displayName/companyName（用于标准化断言）
        String companyCode = null;
        String companyName = null;
        if (currentCompanyId != null) {
            Company company = companyRepo.findById(currentCompanyId).orElse(null);
            if (company != null) {
                companyCode = company.getCode();
                companyName = company.getName();
            }
        }
        String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getUsername();

        // 加载产品授权
        List<LoginResponse.ProductView> products = buildProductView(user.getId(), currentCompanyId, user.getIsPlatformAdmin());

        // 颁发 JWT — Phase 2 解耦版（10 参新方法）
        String token = jwtUtil.generate(
                user.getId(),
                user.getUsername(),
                currentCompanyId,
                companyCode,
                user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin(),
                null,           // productCode: 主网站登录时不绑定具体产品，由 /enter 端点注入
                null,           // roleCode: 主网站不再持有产品内角色
                displayName,    // Phase 2 新增
                companyName,    // Phase 2 新增
                user.getUsername() // Phase 2 新增：授权人
        );

        // 设置 CurrentUser（供后续 Service 使用）— Phase 2 解耦版
        CurrentUser cu = new CurrentUser();
        cu.setUserId(user.getId());
        cu.setUsername(user.getUsername());
        cu.setCompanyId(currentCompanyId);
        cu.setCompanyCode(companyCode);
        cu.setPlatformAdmin(user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin());
        cu.setTenantName(companyName);
        cu.setDisplayName(displayName);
        CurrentUser.set(cu);

        // 审计
        auditLogService.record("LOGIN_SUCCESS", "USER", String.valueOf(user.getId()), "登录成功: " + user.getUsername());

        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setExpiresIn(86400);

        LoginResponse.UserView uv = new LoginResponse.UserView();
        uv.setId(user.getId());
        uv.setUsername(user.getUsername());
        uv.setDisplayName(user.getDisplayName());
        uv.setEmail(user.getEmail());
        uv.setPlatformAdmin(Boolean.TRUE.equals(user.getIsPlatformAdmin()));
        resp.setUser(uv);

        if (currentCompanyId != null) {
            companyRepo.findById(currentCompanyId).ifPresent(c -> {
                LoginResponse.CompanyView cv = new LoginResponse.CompanyView();
                cv.setId(c.getId());
                cv.setCode(c.getCode());
                cv.setName(c.getName());
                resp.setCompany(cv);
            });
        }

        resp.setProducts(products);

        // P0-A：私有化首登强制改密（仅当前用户 passwordChanged=false 时返回 true）
        boolean mustChange = Boolean.FALSE.equals(user.getPasswordChanged());
        resp.setMustChangePassword(mustChange);
        if (mustChange) {
            auditLogService.record("LOGIN_MUST_CHANGE_PASSWORD", "USER", String.valueOf(user.getId()),
                    "首登触发强制改密: " + user.getUsername());
        }

        return resp;
    }

    /**
     * P0-A：修改当前用户密码
     * @param userId       当前用户 ID
     * @param oldPassword  原密码（必须正确，否则抛 IllegalArgumentException）
     * @param newPassword  新密码（≥ 8 位，且不能与用户名相同）
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码长度至少 8 位");
        }
        SysUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("原密码错误");
        }
        if (newPassword.equals(user.getUsername())) {
            throw new IllegalArgumentException("新密码不能与用户名相同");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChanged(true);
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userRepo.save(user);
        attemptService.recordSuccess(user.getUsername());
        auditLogService.record("PASSWORD_CHANGED", "USER", String.valueOf(user.getId()),
                "用户修改密码成功: " + user.getUsername());
    }

    private List<LoginResponse.ProductView> buildProductView(Long userId, Long companyId, Boolean isPlatformAdmin) {
        List<Product> allProducts = productRepo.findAll();
        Set<Long> companyLicensedProductIds = companyProductRepo.findByCompanyIdAndStatus(companyId, CompanyProduct.Status.ACTIVE).stream()
                .map(CompanyProduct::getProductId)
                .collect(Collectors.toSet());
        Set<Long> userGrantedProductIds = grantRepo.findByUserIdAndStatus(userId, ProductUserGrant.Status.ACTIVE).stream()
                .map(ProductUserGrant::getProductId)
                .collect(Collectors.toSet());
        Map<Long, String> userRoleByProductId = userRoleRepo.findByUserId(userId).stream()
                .collect(Collectors.toMap(ProductUserRole::getProductId, ProductUserRole::getRoleCode, (a, b) -> a));

        List<LoginResponse.ProductView> result = new ArrayList<>();
        for (Product p : allProducts) {
            LoginResponse.ProductView pv = new LoginResponse.ProductView();
            pv.setId(p.getId());
            pv.setCode(p.getCode());
            pv.setName(p.getName());
            pv.setIcon(p.getIcon());
            boolean isAdmin = Boolean.TRUE.equals(isPlatformAdmin);
            boolean licensed = isAdmin || companyLicensedProductIds.contains(p.getId());
            boolean granted = isAdmin || (licensed && userGrantedProductIds.contains(p.getId()));
            pv.setLicensed(licensed);
            pv.setGranted(granted);
            String roleCode = userRoleByProductId.get(p.getId());
            pv.setRoleCode(roleCode);
            if (roleCode != null) {
                pv.setRoleName(roleRepo.findByProductIdAndRoleCode(p.getId(), roleCode)
                        .map(ProductRole::getRoleName).orElse(null));
            }
            pv.setEntryPath("/subtask/" + p.getCode());
            result.add(pv);
        }
        return result;
    }
}
