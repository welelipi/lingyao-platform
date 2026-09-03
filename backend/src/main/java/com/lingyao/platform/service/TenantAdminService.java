package com.lingyao.platform.service;

import com.lingyao.platform.dto.admin.BindUserToCompanyRequest;
import com.lingyao.platform.dto.admin.CreateCompanyRequest;
import com.lingyao.platform.dto.admin.CreateCompanyWithAdminRequest;
import com.lingyao.platform.dto.admin.CreateUserRequest;
import com.lingyao.platform.dto.admin.GrantCompanyProductsRequest;
import com.lingyao.platform.dto.admin.GrantProductRequest;
import com.lingyao.platform.dto.admin.UpdateCompanyRequest;
import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import com.lingyao.platform.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @Autowired private TrialInviteService trialInviteService;

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
        // V2.0.10：新建公司默认状态 = PENDING（开通中），由大超管手动改 ACTIVE 完成开通
        company.setStatus(Company.CompanyStatus.PENDING);
        company.setMaxUsers(req.getMaxUsers() != null ? req.getMaxUsers() : 10);
        company.setContactEmail(req.getContactEmail());
        company.setContactPhone(req.getContactPhone());
        company.setAddress(req.getAddress());
        company.setRemark(req.getRemark());
        company.setLicenseStart(LocalDateTime.now());
        company.setLicenseEnd(LocalDateTime.now().plusYears(1));

        Company saved = companyRepo.save(company);

        // 可选：自动授权全部产品（V2.0.54：仅 ACTIVE 产品——DEPRECATED 软下架产品不再自动授权）
        if (Boolean.TRUE.equals(req.getGrantAllProducts())) {
            List<Product> products = productRepo.findByStatusOrderBySortOrderAsc(Product.Status.ACTIVE);
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
     * V2.0.28 方案A：一步创建公司 + 公司管理员（主人 2026-09-02 拍板 A+B）
     *
     * admin 为 null 或 username 空 → 行为同旧版 createCompany（PENDING 公司，不建号）。
     * admin 填写 → 一次事务内：建公司(直接 ACTIVE) + 建管理员(SUPER_ADMIN, ACTIVE) + 绑定 + 随产品授权。
     * 初始密码留空 → 自动生成 12 位强密码（明文仅在返回值中出现一次）。
     */
    @Transactional
    public Map<String, Object> createCompanyWithAdmin(CreateCompanyWithAdminRequest req) {
        CreateCompanyRequest companyReq = req.getCompany();
        if (companyRepo.findByCode(companyReq.getCode()).isPresent()) {
            throw new IllegalArgumentException("公司 code 已存在: " + companyReq.getCode());
        }

        // ── 1. 建公司：携带管理员时直接 ACTIVE（开通仪式一步到位） ──
        CreateCompanyWithAdminRequest.AdminRef adminRef = req.getAdmin();
        boolean withAdmin = adminRef != null
                && adminRef.getUsername() != null && !adminRef.getUsername().isBlank();
        // V2.0.29：试用邀请模式（默认 30 天），主人 2026-09-02 拍板
        // V2 变更（09:21/09:22）：取消"仅 GEO"限制——按实际勾选开通的产品发对应介绍；
        //                         邮件内严禁出现 GEOM/MPDM/AIDD/D-Info 等内部代号
        boolean trialInvite = Boolean.TRUE.equals(req.getTrialInvite()) && withAdmin;
        int trialDays = (req.getTrialDays() != null && req.getTrialDays() > 0) ? req.getTrialDays() : 30;
        Company company;
        if (withAdmin) {
            // createCompany 固定 PENDING，这里先建再激活，审计两条都留痕
            // （试用模式不忽略 grantAllProducts：开通哪个产品，邮件就介绍哪个产品）
            company = createCompany(companyReq);
            company.setStatus(Company.CompanyStatus.ACTIVE);
            if (trialInvite) {
                // 试用模式：TRIAL 许可 + 试用期 = N 天（覆盖默认 1 年）
                company.setLicensePlan(Company.LicensePlan.TRIAL);
                company.setLicenseStart(LocalDateTime.now());
                company.setLicenseEnd(LocalDateTime.now().plusDays(trialDays));
            }
            company = companyRepo.save(company);
            auditLogService.record("COMPANY_ACTIVATE", "COMPANY", String.valueOf(company.getId()),
                    "组合开通直接激活: " + company.getName()
                            + (trialInvite ? " (试用 " + trialDays + " 天)" : ""));
        } else {
            company = createCompany(companyReq);
        }

        // ── 2. 无管理员请求 → 返回公司即可 ──
        if (!withAdmin) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("company", company);
            return result;
        }

        // ── 3. 建管理员（SUPER_ADMIN）+ 绑定 + 授权全部已购产品 ──
        if (userRepo.existsByUsername(adminRef.getUsername())) {
            throw new IllegalArgumentException("用户名已存在: " + adminRef.getUsername());
        }
        String rawPassword = (adminRef.getInitialPassword() != null && !adminRef.getInitialPassword().isBlank())
                ? adminRef.getInitialPassword() : generateRandomPassword(12);

        SysUser admin = new SysUser();
        admin.setUsername(adminRef.getUsername());
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setDisplayName(adminRef.getDisplayName() != null ? adminRef.getDisplayName() : adminRef.getUsername());
        admin.setEmail(adminRef.getEmail());
        admin.setPhone(adminRef.getPhone());
        admin.setStatus(SysUser.UserStatus.ACTIVE);
        admin.setIsPlatformAdmin(false);
        admin.setPasswordChanged(true);
        admin.setLoginMethods("PASSWORD");
        SysUser savedAdmin = userRepo.save(admin);

        CompanyUser cu = new CompanyUser();
        cu.setCompanyId(company.getId());
        cu.setUserId(savedAdmin.getId());
        cu.setRole(CompanyUser.CompanyRole.SUPER_ADMIN);
        cu.setStatus(CompanyUser.Status.ACTIVE);
        companyUserRepo.save(cu);

        // V2.0.29 试用模式兜底：一个产品都没勾时，默认授权 GEO（保证账号有东西可用、邮件有内容可写）
        if (trialInvite && companyProductRepo
                .findByCompanyIdAndStatus(company.getId(), CompanyProduct.Status.ACTIVE).isEmpty()) {
            Product geo = productRepo.findByCode("GEO")
                    .orElseThrow(() -> new IllegalStateException("产品 GEO 不存在，请检查种子数据"));
            CompanyProduct cp = new CompanyProduct();
            cp.setCompanyId(company.getId());
            cp.setProductId(geo.getId());
            cp.setLicenseStart(LocalDateTime.now());
            cp.setLicenseEnd(LocalDateTime.now().plusDays(trialDays));
            cp.setMaxUsers(100);
            cp.setStatus(CompanyProduct.Status.ACTIVE);
            cp.setGrantedBy(currentUserIdOrNull());
            companyProductRepo.save(cp);
            auditLogService.record("COMPANY_GRANT", "COMPANY", String.valueOf(company.getId()),
                    "试用默认授权 GEO (" + trialDays + " 天)");
        }

        // 管理员授权公司已开通的产品（若公司勾选了 grantAllProducts 则此处已全部开通）
        // V2.0.29：CompanyProduct 试用期与公司一致——试用模式下对齐 licenseEnd
        List<Long> grantedProductIds = new ArrayList<>();
        List<String> grantedProductCodes = new ArrayList<>();
        for (CompanyProduct cp : companyProductRepo.findByCompanyIdAndStatus(
                company.getId(), CompanyProduct.Status.ACTIVE)) {
            if (trialInvite) {
                cp.setLicenseStart(company.getLicenseStart());
                cp.setLicenseEnd(company.getLicenseEnd());
                companyProductRepo.save(cp);
            }
            ProductUserGrant grant = new ProductUserGrant();
            grant.setCompanyId(company.getId());
            grant.setProductId(cp.getProductId());
            grant.setUserId(savedAdmin.getId());
            grant.setGrantedBy(currentUserIdOrNull());
            grant.setStatus(ProductUserGrant.Status.ACTIVE);
            grantRepo.save(grant);
            grantedProductIds.add(cp.getProductId());
            productRepo.findById(cp.getProductId()).map(Product::getCode).ifPresent(grantedProductCodes::add);
        }

        auditLogService.record("USER_CREATE", "USER", String.valueOf(savedAdmin.getId()),
                "组合开通建管理员: " + savedAdmin.getUsername() + " @ " + company.getName()
                        + " (SUPER_ADMIN), 授权产品 " + grantedProductIds.size() + " 个");

        // V2.0.29：试用邀请邮件——按实际开通的产品生成介绍，事务提交后异步发送（发信失败不影响开通）
        if (trialInvite) {
            final Company fCompany = company;
            final SysUser fAdmin = savedAdmin;
            final String fPassword = rawPassword;
            final List<String> fCodes = grantedProductCodes.isEmpty() ? List.of("GEO") : grantedProductCodes;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    trialInviteService.sendTrialInviteAsync(fCompany, fAdmin, fPassword, trialDays, fCodes);
                }
            });
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", company);
        result.put("admin", savedAdmin);
        result.put("initialPassword", rawPassword);   // 仅此一次返回明文
        result.put("grantedProductIds", grantedProductIds);
        result.put("grantedProductCodes", grantedProductCodes);
        result.put("trialInvite", trialInvite);
        return result;
    }

    /**
     * 更新公司 — V2.0.10
     *
     * 允许修改字段：name / deploymentMode / licensePlan / status / licenseStart / licenseEnd /
     *               maxUsers / contactEmail / contactPhone / address / remark
     * 禁止修改字段：code（唯一标识，改动风险大；如需改走「迁移」专门接口）
     */
    @Transactional
    public Company updateCompany(Long id, UpdateCompanyRequest req) {
        Company company = companyRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("公司不存在: " + id));

        StringBuilder diff = new StringBuilder("修改公司: ");

        // 名称
        if (!company.getName().equals(req.getName())) {
            diff.append("name: ").append(company.getName()).append(" → ").append(req.getName()).append("; ");
            company.setName(req.getName());
        }
        // 部署模式（高危，前端二次确认）
        Company.DeploymentMode newDeployMode = Company.DeploymentMode.valueOf(req.getDeploymentMode());
        if (company.getDeploymentMode() != newDeployMode) {
            diff.append("deploymentMode: ").append(company.getDeploymentMode())
                    .append(" → ").append(newDeployMode).append("; ");
            company.setDeploymentMode(newDeployMode);
        }
        // 许可证等级
        Company.LicensePlan newLicensePlan = Company.LicensePlan.valueOf(req.getLicensePlan());
        if (company.getLicensePlan() != newLicensePlan) {
            diff.append("licensePlan: ").append(company.getLicensePlan())
                    .append(" → ").append(newLicensePlan).append("; ");
            company.setLicensePlan(newLicensePlan);
        }
        // 状态（高危，前端二次确认）
        Company.CompanyStatus newStatus = Company.CompanyStatus.valueOf(req.getStatus());
        if (company.getStatus() != newStatus) {
            diff.append("status: ").append(company.getStatus())
                    .append(" → ").append(newStatus).append("; ");
            company.setStatus(newStatus);
        }
        // 许可证起始（前端二次确认）
        if (req.getLicenseStart() != null
                && (company.getLicenseStart() == null
                        || !req.getLicenseStart().equals(company.getLicenseStart()))) {
            diff.append("licenseStart: ").append(company.getLicenseStart())
                    .append(" → ").append(req.getLicenseStart()).append("; ");
            company.setLicenseStart(req.getLicenseStart());
        }
        // 许可证截止（前端二次确认）
        if (req.getLicenseEnd() != null
                && (company.getLicenseEnd() == null
                        || !req.getLicenseEnd().equals(company.getLicenseEnd()))) {
            diff.append("licenseEnd: ").append(company.getLicenseEnd())
                    .append(" → ").append(req.getLicenseEnd()).append("; ");
            company.setLicenseEnd(req.getLicenseEnd());
        }
        // 最大用户数
        if (req.getMaxUsers() != null && !req.getMaxUsers().equals(company.getMaxUsers())) {
            diff.append("maxUsers: ").append(company.getMaxUsers())
                    .append(" → ").append(req.getMaxUsers()).append("; ");
            company.setMaxUsers(req.getMaxUsers());
        }
        // 联系邮箱
        if (!safeEq(company.getContactEmail(), req.getContactEmail())) {
            diff.append("contactEmail: ").append(company.getContactEmail())
                    .append(" → ").append(req.getContactEmail()).append("; ");
            company.setContactEmail(req.getContactEmail());
        }
        // 联系电话
        if (!safeEq(company.getContactPhone(), req.getContactPhone())) {
            diff.append("contactPhone: ").append(company.getContactPhone())
                    .append(" → ").append(req.getContactPhone()).append("; ");
            company.setContactPhone(req.getContactPhone());
        }
        // 地址
        if (!safeEq(company.getAddress(), req.getAddress())) {
            company.setAddress(req.getAddress());
        }
        // 备注
        if (!safeEq(company.getRemark(), req.getRemark())) {
            company.setRemark(req.getRemark());
        }

        Company saved = companyRepo.save(company);

        if (diff.length() > "修改公司: ".length()) {
            auditLogService.record("COMPANY_UPDATE", "COMPANY", String.valueOf(saved.getId()),
                    diff.toString());
        }
        return saved;
    }

    private static boolean safeEq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
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

        // 绑定公司 + 角色（V2.0.47：主站只建公司超管，角色强制归一化 SUPER_ADMIN）
        if (req.getMemberships() != null) {
            for (CreateUserRequest.BindCompanyRef ref : req.getMemberships()) {
                CompanyUser cu = new CompanyUser();
                cu.setCompanyId(ref.getCompanyId());
                cu.setUserId(saved.getId());
                cu.setRole(CompanyUser.CompanyRole.SUPER_ADMIN);
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
        // V2.0.47：主站绑定一律 = 公司超管（与邀请唯一角色对齐；普通成员由公司超管在子平台内添加）
        cu.setRole(CompanyUser.CompanyRole.SUPER_ADMIN);
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
     *
     * V2.0.43 语义修正：改为「全量同步」——弹窗勾选即公司最终产品集合。
     * 勾选 = 开通/续期（原逻辑）；取消勾选 = 公司 license 置 SUSPENDED + 级联 SUSPEND 该公司用户的用户级授权。
     * 此前只做追加，取消勾选不生效（主人报：A药企 5 产品减 4 个不生效）。
     */
    @Transactional
    public List<CompanyProduct> grantCompanyProducts(GrantCompanyProductsRequest req) {
        Company company = companyRepo.findById(req.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("公司不存在: " + req.getCompanyId()));

        Set<Long> requested = new HashSet<>(req.getProductIds());

        // 当前 ACTIVE 的公司产品
        List<CompanyProduct> activeList = companyProductRepo.findByCompanyIdAndStatus(
                req.getCompanyId(), CompanyProduct.Status.ACTIVE);
        Set<Long> existing = new HashSet<>();
        for (CompanyProduct cp : activeList) {
            existing.add(cp.getProductId());
        }

        int maxUsers = req.getMaxUsers() != null ? req.getMaxUsers() : 100;
        int years = req.getLicenseYears() != null ? req.getLicenseYears() : 10;
        Long grantedBy = currentUserIdOrNull();

        List<CompanyProduct> results = new ArrayList<>();
        for (Long productId : req.getProductIds()) {
            // V2.0.43：先查任意状态的历史记录（含 SUSPENDED/EXPIRED/CANCELLED）——
            // uk_company_product(company_id, product_id) 唯一约束下不能 INSERT 撞已有行；
            // ACTIVE → 续期；非 ACTIVE → 复活；无记录 → 新建
            CompanyProduct cp = companyProductRepo
                    .findByCompanyIdAndProductId(req.getCompanyId(), productId)
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

        // V2.0.43：取消勾选的产品 → 公司 license SUSPENDED + 级联挂起用户级授权
        List<Long> revoked = new ArrayList<>();
        for (CompanyProduct cp : activeList) {
            if (requested.contains(cp.getProductId())) continue;
            cp.setStatus(CompanyProduct.Status.SUSPENDED);
            companyProductRepo.save(cp);
            revoked.add(cp.getProductId());
            suspendUserGrantsForCompanyProduct(req.getCompanyId(), cp.getProductId());
        }

        String auditMsg = "为 " + company.getName() + " 同步产品：开通 " + req.getProductIds().size()
                + " 个" + (revoked.isEmpty() ? "" : "，停用 " + revoked.size() + " 个(产品ID:" + revoked + ")");
        auditLogService.record("COMPANY_GRANT_PRODUCT", "COMPANY", String.valueOf(req.getCompanyId()), auditMsg);
        return results;
    }

    /**
     * V2.0.43：公司产品被停用时，级联挂起该公司用户在该产品上的 ACTIVE 授权。
     * 恢复公司 license（重新勾选）时授权记录仍在——重新授权走既有 grantProductToUser，或恢复时手工处理。
     */
    private void suspendUserGrantsForCompanyProduct(Long companyId, Long productId) {
        for (CompanyUser cu : companyUserRepo.findByCompanyId(companyId)) {
            for (ProductUserGrant g : grantRepo.findByCompanyIdAndUserIdAndStatus(
                    companyId, cu.getUserId(), ProductUserGrant.Status.ACTIVE)) {
                if (!productId.equals(g.getProductId())) continue;
                g.setStatus(ProductUserGrant.Status.REVOKED);
                grantRepo.save(g);
            }
        }
    }

    @Transactional
    public Company suspendCompany(Long companyId) {
        Company company = companyRepo.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("公司不存在: " + companyId));
        if (company.getStatus() == Company.CompanyStatus.SUSPENDED) {
            throw new IllegalArgumentException("公司已处于禁用状态");
        }
        if (company.getStatus() == Company.CompanyStatus.DELETED) {
            throw new IllegalArgumentException("已删除的公司不可禁用");
        }
        company.setStatus(Company.CompanyStatus.SUSPENDED);
        Company saved = companyRepo.save(company);
        auditLogService.record("COMPANY_SUSPEND", "COMPANY", String.valueOf(companyId),
                "禁用公司: " + company.getName());
        return saved;
    }

    @Transactional
    public Company activateCompany(Long companyId) {
        Company company = companyRepo.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("公司不存在: " + companyId));
        if (company.getStatus() == Company.CompanyStatus.DELETED) {
            throw new IllegalArgumentException("已删除的公司不可激活");
        }
        if (company.getStatus() == Company.CompanyStatus.ACTIVE) {
            throw new IllegalArgumentException("公司已处于激活状态");
        }
        company.setStatus(Company.CompanyStatus.ACTIVE);
        Company saved = companyRepo.save(company);
        auditLogService.record("COMPANY_ACTIVATE", "COMPANY", String.valueOf(companyId),
                "激活公司: " + company.getName());
        return saved;
    }

    private static final String RESET_PWD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%^&*";
    private static final SecureRandom RESET_PWD_RANDOM = new SecureRandom();

    @Transactional
    public ResetPasswordResult resetUserPassword(Long userId) {
        SysUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        String newPassword = generateRandomPassword(12);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChanged(false);
        user.setUpdatedAt(LocalDateTime.now());
        SysUser saved = userRepo.save(user);
        auditLogService.record("USER_RESET_PASSWORD", "USER", String.valueOf(userId),
                "重置用户密码: " + saved.getUsername());
        return new ResetPasswordResult(saved.getUsername(), newPassword);
    }

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RESET_PWD_CHARS.charAt(RESET_PWD_RANDOM.nextInt(RESET_PWD_CHARS.length())));
        }
        return sb.toString();
    }

    public static class ResetPasswordResult {
        private final String username;
        private final String newPassword;
        public ResetPasswordResult(String username, String newPassword) {
            this.username = username;
            this.newPassword = newPassword;
        }
        public String getUsername() { return username; }
        public String getNewPassword() { return newPassword; }
    }
}
