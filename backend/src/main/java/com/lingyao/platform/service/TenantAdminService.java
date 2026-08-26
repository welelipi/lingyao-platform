package com.lingyao.platform.service;

import com.lingyao.platform.dto.admin.BindUserToCompanyRequest;
import com.lingyao.platform.dto.admin.CreateCompanyRequest;
import com.lingyao.platform.dto.admin.CreateUserRequest;
import com.lingyao.platform.dto.admin.GrantCompanyProductsRequest;
import com.lingyao.platform.dto.admin.GrantProductRequest;
import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import com.lingyao.platform.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 平台超管服务 — P1-B
 *
 * 负责：
 * 1. 创建公司（含 code 唯一性校验、可选授权全部产品）
 * 2. 创建用户（含密码加密、首次登录是否强制改密、绑定公司、授权产品）
 * 3. 用户绑定到公司
 * 4. 授予/撤销用户产品访问权
 *
 * 所有操作仅允许 platformAdmin=true 的调用方执行（在 Controller 层校验）。
 */
@Service
public class TenantAdminService {

    @Autowired private CompanyRepository companyRepo;
    @Autowired private SysUserRepository userRepo;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private CompanyProductRepository companyProductRepo;
    @Autowired private ProductUserGrantRepository grantRepo;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditLogService auditLogService;

    /**
     * 创建公司
     */
    @Transactional
    public Company createCompany(CreateCompanyRequest req) {
        if (companyRepo.findByCode(req.getCode()).isPresent()) {
            throw new IllegalArgumentException("公司 code 已存在: " + req.getCode());
        }

        Company company = new Company();
        company.setName(req.getName());
        company.setCode(req.getCode());
        company.setDeploymentMode(Company.DeploymentMode.valueOf(req.getDeploymentMode()));
        company.setLicensePlan(Company.LicensePlan.valueOf(req.getLicensePlan()));
        company.setStatus(Company.CompanyStatus.ACTIVE);
        company.setMaxUsers(req.getMaxUsers() != null ? req.getMaxUsers() : 10);
        company.setContactEmail(req.getContactEmail());
        company.setContactPhone(req.getContactPhone());
        company.setAddress(req.getAddress());
        company.setRemark(req.getRemark());
        company.setLicenseStart(LocalDateTime.now());
        company.setLicenseEnd(LocalDateTime.now().plusYears(1));

        Company saved = companyRepo.save(company);

        // 可选：自动授权全部产品
        if (Boolean.TRUE.equals(req.getGrantAllProducts())) {
            List<Product> products = productRepo.findAll();
            for (Product p : products) {
                CompanyProduct cp = new CompanyProduct();
                cp.setCompanyId(saved.getId());
                cp.setProductId(p.getId());
                cp.setLicenseStart(LocalDateTime.now());
                cp.setLicenseEnd(LocalDateTime.now().plusYears(10));
                cp.setMaxUsers(100);
                cp.setStatus(CompanyProduct.Status.ACTIVE);
                cp.setGrantedBy(currentUserIdOrNull());
                companyProductRepo.save(cp);
            }
        }

        auditLogService.record("COMPANY_CREATE", "COMPANY", String.valueOf(saved.getId()),
                "创建公司: " + saved.getName() + " (" + saved.getCode() + ")");
        return saved;
    }

    /**
     * 创建用户
     */
    @Transactional
    public SysUser createUser(CreateUserRequest req) {
        if (userRepo.existsByUsername(req.getUsername())) {
            throw new IllegalArgumentException("用户名已存在: " + req.getUsername());
        }

        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getInitialPassword()));
        user.setDisplayName(req.getDisplayName());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(SysUser.UserStatus.ACTIVE);
        user.setIsPlatformAdmin(Boolean.TRUE.equals(req.getPlatformAdmin()));
        user.setPasswordChanged(true);  // 管理员代建 → 默认已改密
        user.setLoginMethods("PASSWORD");

        SysUser saved = userRepo.save(user);

        // 绑定公司 + 角色
        if (req.getMemberships() != null) {
            for (CreateUserRequest.BindCompanyRef ref : req.getMemberships()) {
                CompanyUser cu = new CompanyUser();
                cu.setCompanyId(ref.getCompanyId());
                cu.setUserId(saved.getId());
                cu.setRole(CompanyUser.CompanyRole.valueOf(
                        ref.getCompanyRole() == null ? "OPERATOR" : ref.getCompanyRole()));
                cu.setStatus(CompanyUser.Status.ACTIVE);
                companyUserRepo.save(cu);
            }
        }

        // 授权产品（在第一个 membership 公司内）
        if (req.getProductIds() != null && !req.getProductIds().isEmpty()
                && req.getMemberships() != null && !req.getMemberships().isEmpty()) {
            Long companyId = req.getMemberships().get(0).getCompanyId();
            for (Long productId : req.getProductIds()) {
                grantProductInternal(saved.getId(), companyId, productId);
            }
        }

        auditLogService.record("USER_CREATE", "USER", String.valueOf(saved.getId()),
                "创建用户: " + saved.getUsername()
                        + (req.getMemberships() != null ? "，绑定 " + req.getMemberships().size() + " 个公司" : ""));
        return saved;
    }

    /**
     * 把已有用户绑定到公司
     */
    @Transactional
    public CompanyUser bindUserToCompany(BindUserToCompanyRequest req) {
        SysUser user = userRepo.findById(req.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + req.getUserId()));
        Company company = companyRepo.findById(req.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("公司不存在: " + req.getCompanyId()));

        if (companyUserRepo.existsByCompanyIdAndUserId(req.getCompanyId(), req.getUserId())) {
            throw new IllegalArgumentException("用户已是该公司成员");
        }

        CompanyUser cu = new CompanyUser();
        cu.setCompanyId(req.getCompanyId());
        cu.setUserId(req.getUserId());
        cu.setRole(CompanyUser.CompanyRole.valueOf(
                req.getCompanyRole() == null ? "OPERATOR" : req.getCompanyRole()));
        cu.setStatus(CompanyUser.Status.ACTIVE);
        CompanyUser saved = companyUserRepo.save(cu);

        auditLogService.record("USER_BIND_COMPANY", "COMPANY_USER", String.valueOf(saved.getId()),
                "用户 " + user.getUsername() + " 绑定到公司 " + company.getName()
                        + " (" + req.getCompanyRole() + ")");
        return saved;
    }

    /**
     * 授予用户产品访问权（用户 × 公司 × 产品）
     */
    @Transactional
    public List<ProductUserGrant> grantProducts(GrantProductRequest req) {
        // 校验：用户必须是该公司的成员
        if (!companyUserRepo.existsByCompanyIdAndUserId(req.getCompanyId(), req.getUserId())) {
            throw new IllegalArgumentException(
                    "用户 " + req.getUserId() + " 不是公司 " + req.getCompanyId() + " 的成员，请先绑定");
        }

        // 校验：公司必须购买这些产品
        Set<Long> licensedProductIds = new HashSet<>();
        for (CompanyProduct cp : companyProductRepo.findByCompanyIdAndStatus(
                req.getCompanyId(), CompanyProduct.Status.ACTIVE)) {
            licensedProductIds.add(cp.getProductId());
        }
        for (Long pid : req.getProductIds()) {
            if (!licensedProductIds.contains(pid)) {
                throw new IllegalArgumentException(
                        "公司未购买产品 " + pid + "，请先在公司授权中添加该产品");
            }
        }

        List<ProductUserGrant> results = new ArrayList<>();
        for (Long productId : req.getProductIds()) {
            ProductUserGrant g = grantProductInternal(req.getUserId(), req.getCompanyId(), productId);
            results.add(g);
        }

        SysUser user = userRepo.findById(req.getUserId()).orElse(null);
        auditLogService.record("PRODUCT_GRANT", "USER", String.valueOf(req.getUserId()),
                "为用户 " + (user != null ? user.getUsername() : req.getUserId())
                        + " 授权 " + req.getProductIds().size() + " 个产品");
        return results;
    }

    /**
     * 内部 grant：避免重复（unique key company_id+product_id+user_id）
     */
    private ProductUserGrant grantProductInternal(Long userId, Long companyId, Long productId) {
        return grantRepo.findByCompanyIdAndProductIdAndUserId(companyId, productId, userId)
                .map(existing -> {
                    existing.setStatus(ProductUserGrant.Status.ACTIVE);
                    return grantRepo.save(existing);
                })
                .orElseGet(() -> {
                    ProductUserGrant g = new ProductUserGrant();
                    g.setCompanyId(companyId);
                    g.setProductId(productId);
                    g.setUserId(userId);
                    g.setGrantedBy(currentUserIdOrNull());
                    g.setStatus(ProductUserGrant.Status.ACTIVE);
                    return grantRepo.save(g);
                });
    }

    private Long currentUserIdOrNull() {
        CurrentUser cu = CurrentUser.get();
        return cu == null ? null : cu.getUserId();
    }

    /**
     * 为公司开通多个产品（公司级 license）
     */
    @Transactional
    public List<CompanyProduct> grantCompanyProducts(GrantCompanyProductsRequest req) {
        Company company = companyRepo.findById(req.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("公司不存在: " + req.getCompanyId()));

        // 已开通的产品集合
        Set<Long> existingProductIds = new HashSet<>();
        for (CompanyProduct cp : companyProductRepo.findByCompanyIdAndStatus(
                req.getCompanyId(), CompanyProduct.Status.ACTIVE)) {
            existingProductIds.add(cp.getProductId());
        }

        int maxUsers = req.getMaxUsers() != null ? req.getMaxUsers() : 100;
        int years = req.getLicenseYears() != null ? req.getLicenseYears() : 10;
        Long grantedBy = currentUserIdOrNull();

        List<CompanyProduct> results = new ArrayList<>();
        for (Long productId : req.getProductIds()) {
            // 已开通 → 更新 license_end
            // 未开通 → 新建
            CompanyProduct cp = companyProductRepo
                    .findByCompanyIdAndProductIdAndStatus(
                            req.getCompanyId(), productId, CompanyProduct.Status.ACTIVE)
                    .orElse(null);
            if (cp == null) {
                cp = new CompanyProduct();
                cp.setCompanyId(req.getCompanyId());
                cp.setProductId(productId);
                cp.setLicenseStart(LocalDateTime.now());
            }
            cp.setLicenseEnd(LocalDateTime.now().plusYears(years));
            cp.setMaxUsers(maxUsers);
            cp.setStatus(CompanyProduct.Status.ACTIVE);
            cp.setGrantedBy(grantedBy);
            results.add(companyProductRepo.save(cp));
        }

        auditLogService.record("COMPANY_GRANT_PRODUCT", "COMPANY", String.valueOf(req.getCompanyId()),
                "为 " + company.getName() + " 开通 " + req.getProductIds().size() + " 个产品");
        return results;
    }
}
