package com.lingyao.platform.service;

import com.lingyao.platform.dto.TrialRegistrationRequest;
import com.lingyao.platform.entity.Company;
import com.lingyao.platform.entity.CompanyUser;
import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.entity.TrialHealthScore;
import com.lingyao.platform.entity.TrialRegistration;
import com.lingyao.platform.repository.CompanyRepository;
import com.lingyao.platform.repository.CompanyUserRepository;
import com.lingyao.platform.repository.SysUserRepository;
import com.lingyao.platform.repository.TrialHealthScoreRepository;
import com.lingyao.platform.repository.TrialRegistrationRepository;
import com.lingyao.platform.security.RateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Trial 注册服务 · V2.0.78.0（主站 D-5 Day 1）
 *
 * 职责（V2.0.78.0 最小可用版）：
 * 1. 频次限流（手机号 + IP 各 24h × 3 次 · 主人 09-13 14:25 拍板推荐）
 * 2. 创建 trial_registration 记录（status=PENDING）
 * 3. 自动开通 trial 公司（company.trial_expires_at = NOW + 7d · geom_plan=trial）
 * 4. 创建 trial 公司超管（默认密码 = 手机号后 6 位 · 首次登录强制改密）
 * 5. 绑定 CompanyUser（role=SUPER_ADMIN）
 * 6. 发送开通邮件（复用 TrialInviteService.sendTrialInviteAsync）
 * 7. status: PENDING → ACTIVATED
 *
 * Day 2/3 待补：trial 期产品开通 sub_task 流水线（属后续 GEOM 配合边界）
 *
 * 失败处理：任何步骤失败 → 整体回滚 + 记录 ERROR 日志
 */
@Service
public class TrialRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(TrialRegistrationService.class);

    /** 限流阈值：24h × 3 次（手机号 + IP 各算一次） */
    private static final int RATE_LIMIT_MAX = 3;
    private static final long RATE_LIMIT_WINDOW_MS = 24 * 60 * 60 * 1000L;

    /** trial 试用期 7 天（拍板 V2.0.77.22 D-4 + V2.0.78.0） */
    private static final int TRIAL_DAYS = 7;

    @Autowired private TrialRegistrationRepository trialRepo;
    @Autowired private CompanyRepository companyRepo;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private SysUserRepository sysUserRepo;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RateLimiterService rateLimiter;
    @Autowired private TrialInviteService trialInviteService;
    @Autowired private TrialHealthScoreRepository healthScoreRepo;

    /**
     * 注册入口（频次限流 + 业务创建 + 开通邮件）
     */
    @Transactional
    public TrialRegistration register(TrialRegistrationRequest req, String ipAddress) {
        // ── 1. 频次限流（手机号 + IP） ─────────
        LocalDateTime since = LocalDateTime.now().minusHours(24);

        if (!rateLimiter.allow("trial-register-phone", req.getPhone(), RATE_LIMIT_MAX, RATE_LIMIT_WINDOW_MS)) {
            long count = trialRepo.countByPhoneSince(req.getPhone(), since);
            throw new IllegalArgumentException(
                String.format("同一手机号 24 小时内最多注册 %d 次，您已注册 %d 次", RATE_LIMIT_MAX, count));
        }
        if (ipAddress != null && !rateLimiter.allow("trial-register-ip", ipAddress, RATE_LIMIT_MAX, RATE_LIMIT_WINDOW_MS)) {
            long count = trialRepo.countByIpSince(ipAddress, since);
            throw new IllegalArgumentException(
                String.format("同一 IP 24 小时内最多注册 %d 次，已注册 %d 次", RATE_LIMIT_MAX, count));
        }

        // ── 2. 先查重（手机号已注册则拒） ─────
        if (trialRepo.findByPhone(req.getPhone()).isPresent()) {
            throw new IllegalArgumentException("手机号 " + req.getPhone() + " 已注册过试用，请直接登录");
        }

        // ── 3. 创建 TrialRegistration（PENDING） ─
        TrialRegistration reg = new TrialRegistration();
        reg.setPhone(req.getPhone());
        reg.setEmail(req.getEmail());
        reg.setCompanyName(req.getCompanyName());
        reg.setSource(req.getSource());
        reg.setStatus(TrialRegistration.Status.PENDING);
        reg.setIpAddress(ipAddress);
        reg = trialRepo.save(reg);
        log.info("[Trial] registered id={} phone={} email={} ip={}", reg.getId(), req.getPhone(), req.getEmail(), ipAddress);

        try {
            // ── 3. 创建 trial 公司 ────────────
            String companyCode = ("TRIAL" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
            Company company = new Company();
            company.setName(req.getCompanyName());
            company.setCode(companyCode);
            company.setContactEmail(req.getEmail());
            company.setContactPhone(req.getPhone());
            company.setTrialExpiresAt(LocalDateTime.now().plusDays(TRIAL_DAYS));
            company.setGeomPlan("trial");  // D-3 套餐：trial 档
            company.setLicensePlan(Company.LicensePlan.TRIAL);
            company.setStatus(Company.CompanyStatus.ACTIVE);
            company.setMaxUsers(1);  // D-4 trial 契约：1 席位
            company = companyRepo.save(company);
            log.info("[Trial] created company id={} code={} expiresAt={}", company.getId(), companyCode, company.getTrialExpiresAt());

            // ── 4. 创建 trial 公司超管（用户名 = 手机号，默认密码 = 后 6 位） ─
            String defaultPassword = req.getPhone().substring(req.getPhone().length() - 6);
            SysUser sysUser = new SysUser();
            sysUser.setUsername(req.getPhone());
            sysUser.setPasswordHash(passwordEncoder.encode(defaultPassword));
            sysUser.setDisplayName("trial-" + req.getPhone());
            sysUser.setEmail(req.getEmail());
            sysUser.setPhone(req.getPhone());
            sysUser.setStatus(SysUser.UserStatus.ACTIVE);
            sysUser.setIsPlatformAdmin(false);  // 非平台超管
            sysUser.setPasswordChanged(false);  // 首次登录强制改密
            sysUser = sysUserRepo.save(sysUser);
            log.info("[Trial] created sysUser id={} username={}", sysUser.getId(), req.getPhone());

            // ── 5. 绑定 CompanyUser（role=SUPER_ADMIN） ──
            CompanyUser companyUser = new CompanyUser();
            companyUser.setCompanyId(company.getId());
            companyUser.setUserId(sysUser.getId());
            companyUser.setRole(CompanyUser.CompanyRole.SUPER_ADMIN);
            companyUserRepo.save(companyUser);

            // ── 6. 发送开通邮件（异步 · 失败不阻塞） ──
            try {
                // V2.0.78.0 Day 1：trial 期产品开通属 Day 2/3 范围（需 GEOM 配合），
                // 当前邮件不指定产品列表 → 默认全产品介绍
                java.util.List<String> defaultProducts = java.util.Arrays.asList("GEO", "HPD", "AIDD", "DINFO", "PORM");
                trialInviteService.sendTrialInviteAsync(company, sysUser, defaultPassword, TRIAL_DAYS, defaultProducts);
            } catch (Exception e) {
                log.error("[Trial] send invite email failed companyId={} userId={}", company.getId(), sysUser.getId(), e);
            }

            // ── 7. 更新 TrialRegistration → ACTIVATED ─
            reg.setStatus(TrialRegistration.Status.ACTIVATED);
            reg.setCompanyId(company.getId());
            reg.setTrialExpiresAt(company.getTrialExpiresAt());
            reg = trialRepo.save(reg);

            // ── 7.5. 建初始 TrialHealthScore（V2.0.78.1 Day 2） ──
            TrialHealthScore initialScore = new TrialHealthScore();
            initialScore.setRegistrationId(reg.getId());
            initialScore.setCompanyId(company.getId());
            initialScore.setUserId(sysUser.getId());
            initialScore.setScore(0);
            initialScore.setBand(TrialHealthScore.Band.INACTIVE);
            initialScore.setScoreDate(LocalDateTime.now());
            initialScore.setLoginCount(0);
            initialScore.setInspectionCount(0);
            initialScore.setRemark("initial registration");
            healthScoreRepo.save(initialScore);

            log.info("[Trial] ACTIVATED id={} companyId={} expiresAt={}", reg.getId(), company.getId(), reg.getTrialExpiresAt());
            return reg;

        } catch (Exception e) {
            log.error("[Trial] register failed id={} phone={}", reg.getId(), req.getPhone(), e);
            throw new RuntimeException("试用开通失败：" + e.getMessage(), e);
        }
    }
}
