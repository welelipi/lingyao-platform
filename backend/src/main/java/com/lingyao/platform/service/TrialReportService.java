package com.lingyao.platform.service;

import com.lingyao.platform.entity.TrialHealthScore;
import com.lingyao.platform.entity.TrialRegistration;
import com.lingyao.platform.repository.TrialRegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Trial 周报/月报服务 · V2.0.78.1（主站 D-5 Day 2）
 *
 * 主人 09-13 14:25 拍板：④ 周报/月报发送方 → A 主站代发
 *
 * 职责：
 * - sendWeeklyReport：每周一 09:00 给 HEALTHY+WATCH 的 trial 发周报
 * - sendMonthlyReport：每月 1 号 09:00 给所有 ACTIVATED trial 发月报
 *
 * V2.0.78.1 简化实现：
 * - 不引入新邮件模板类（避免过度设计）
 * - 仅写日志（V2.0.78.2 Day 3 接入实际邮件模板 + GEOM 数据丰富内容）
 *
 * GEOM 配合（第二批 · 不在 Day 2 范围）：
 * - 邮件里"本周活跃度"数据应来自 GEOM trial-status 端点
 * - V2.0.78.1 仅用主站 trial_health_score 表信号
 */
@Service
public class TrialReportService {

    private static final Logger log = LoggerFactory.getLogger(TrialReportService.class);

    @Autowired private TrialRegistrationRepository trialRepo;

    /**
     * 周报：发送 Trial 周度数据汇总
     * V2.0.78.1 简化：仅日志（Day 3 接入完整邮件模板）
     */
    public void sendWeeklyReport(Long registrationId, TrialHealthScore score) {
        TrialRegistration reg = trialRepo.findById(registrationId).orElse(null);
        if (reg == null) return;

        long daysElapsed = ChronoUnit.DAYS.between(reg.getTrialExpiresAt().minusDays(7), LocalDateTime.now());
        log.info("[TrialReport] weekly regId={} email={} band={} score={} daysElapsed={}",
                registrationId, reg.getEmail(), score.getBand(), score.getScore(), daysElapsed);
        // TODO(V2.0.78.2 Day 3)：调用 trialInviteService.sendWeeklyEmailAsync(...)
    }

    /**
     * 月报：发送 Trial 月度数据汇总（含不活跃提醒）
     * V2.0.78.1 简化：仅日志（Day 3 接入完整邮件模板）
     */
    public void sendMonthlyReport(Long registrationId, TrialHealthScore score) {
        TrialRegistration reg = trialRepo.findById(registrationId).orElse(null);
        if (reg == null) return;

        log.info("[TrialReport] monthly regId={} email={} band={} score={} login={} insp={}",
                registrationId, reg.getEmail(),
                score != null ? score.getBand() : "N/A",
                score != null ? score.getScore() : 0,
                score != null ? score.getLoginCount() : 0,
                score != null ? score.getInspectionCount() : 0);
        // TODO(V2.0.78.2 Day 3)：调用 trialInviteService.sendMonthlyEmailAsync(...)
    }
}
