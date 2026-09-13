package com.lingyao.platform.service;

import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Trial 健康度分服务 · V2.0.78.1（主站 D-5 Day 2）
 *
 * 主人 09-13 14:25 拍板：⑤ 健康度分诊计算 → A 主站算
 *
 * 计算规则（每日 02:00 cron 跑批）：
 * - login_count > 3 = +30（活跃登录）
 * - inspection_count > 1 = +40（深度使用）
 * - last_active_at 在 3 天内 = +30（近期活跃）
 * - 总分 0~100，70+ = HEALTHY / 40~69 = WATCH / <40 = INACTIVE
 *
 * 信号源（主站已有）：
 * - sys_user.lastLoginAt / lastActiveAt（登录活跃度）
 * - sys_user.loginMethods（登录方式数 · 暂不计入）
 * - trial_registration.firstInspectionAt（巡检触发 · 已 V2.0.78.1 首巡检 cron 写）
 *
 * GEOM 配合（第二批 · 不在 Day 2 范围）：
 * - GET /api/users/trial-status?phone=XXX → 拉 GEOM 端业务数据
 *   → 接入后可丰富 login_count（产品内登录）/ inspection_count（产品内操作）
 */
@Service
public class TrialHealthScoreService {

    private static final Logger log = LoggerFactory.getLogger(TrialHealthScoreService.class);

    /** 跑批日窗口起点：当日 00:00:00 */
    private static final int HEALTHY_THRESHOLD = 70;
    private static final int WATCH_THRESHOLD = 40;

    @Autowired private TrialHealthScoreRepository scoreRepo;
    @Autowired private TrialRegistrationRepository trialRepo;
    @Autowired private SysUserRepository sysUserRepo;
    @Autowired private CompanyRepository companyRepo;

    /**
     * 计算单个 trial 的健康度分
     */
    @Transactional
    public TrialHealthScore computeOne(Long registrationId) {
        TrialRegistration reg = trialRepo.findById(registrationId).orElse(null);
        if (reg == null || reg.getStatus() != TrialRegistration.Status.ACTIVATED) {
            return null;
        }

        // 找或创建 score 记录
        TrialHealthScore score = scoreRepo.findByRegistrationId(registrationId).orElseGet(() -> {
            TrialHealthScore s = new TrialHealthScore();
            s.setRegistrationId(registrationId);
            s.setCompanyId(reg.getCompanyId());
            // 用户 ID 需要从 sysUser 表查（username = phone）
            sysUserRepo.findByUsername(reg.getPhone()).ifPresent(u -> s.setUserId(u.getId()));
            return s;
        });

        // ── 信号 1：登录次数 ──────────
        SysUser user = sysUserRepo.findByUsername(reg.getPhone()).orElse(null);
        int loginCount = 0;
        LocalDateTime lastActiveAt = null;
        if (user != null) {
            // 简化：以 lastLoginAt 是否存在 + 近 7 天 = login_count 估算
            // 真实 login_count 需要 SsoLoginAudit 聚合（V2.0.78.2 Day 3 完善）
            if (user.getLastLoginAt() != null) {
                loginCount = 1;  // 占位 · Day 3 用 SsoLoginAudit COUNT 替换
                if (user.getLastActiveAt() != null) {
                    // 后续登录过则 +1（lastActiveAt > lastLoginAt）
                    if (user.getLastActiveAt().isAfter(user.getLastLoginAt())) {
                        loginCount = 2;
                    }
                }
            }
            lastActiveAt = user.getLastActiveAt() != null ? user.getLastActiveAt() : user.getLastLoginAt();
        }

        // ── 信号 2：巡检次数（首次巡检算 1） ──
        int inspectionCount = reg.getFirstInspectionAt() != null ? 1 : 0;

        // ── 计算分数 ──────────────
        int total = 0;
        StringBuilder remark = new StringBuilder();
        if (loginCount > 3) { total += 30; remark.append("login>3+30 "); }
        if (inspectionCount > 1) { total += 40; remark.append("insp>1+40 "); }
        if (lastActiveAt != null && ChronoUnit.DAYS.between(lastActiveAt, LocalDateTime.now()) <= 3) {
            total += 30; remark.append("active≤3d+30 ");
        }

        // ── 分桶 ────────────────
        TrialHealthScore.Band band;
        if (total >= HEALTHY_THRESHOLD) band = TrialHealthScore.Band.HEALTHY;
        else if (total >= WATCH_THRESHOLD) band = TrialHealthScore.Band.WATCH;
        else band = TrialHealthScore.Band.INACTIVE;

        score.setScore(Math.min(total, 100));
        score.setBand(band);
        score.setScoreDate(LocalDateTime.now());
        score.setLoginCount(loginCount);
        score.setInspectionCount(inspectionCount);
        score.setLastActiveAt(lastActiveAt);
        score.setRemark(remark.toString().trim());

        score = scoreRepo.save(score);
        log.info("[HealthScore] computed regId={} score={} band={}", registrationId, score.getScore(), band);
        return score;
    }

    /**
     * 跑批：每日 02:00 重算所有 ACTIVATED trial
     */
    @Transactional
    public int recomputeAll() {
        LocalDateTime today = LocalDateTime.now().toLocalDate().atStartOfDay();
        List<TrialHealthScore> stale = scoreRepo.findStaleScores(today);
        int count = 0;
        for (TrialHealthScore s : stale) {
            try {
                computeOne(s.getRegistrationId());
                count++;
            } catch (Exception e) {
                log.error("[HealthScore] compute failed regId={}", s.getRegistrationId(), e);
            }
        }
        log.info("[HealthScore] recomputeAll done count={}", count);
        return count;
    }
}
