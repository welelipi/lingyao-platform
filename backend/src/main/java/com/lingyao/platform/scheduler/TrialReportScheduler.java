package com.lingyao.platform.scheduler;

import com.lingyao.platform.entity.TrialHealthScore;
import com.lingyao.platform.entity.TrialRegistration;
import com.lingyao.platform.repository.TrialHealthScoreRepository;
import com.lingyao.platform.repository.TrialRegistrationRepository;
import com.lingyao.platform.service.TrialHealthScoreService;
import com.lingyao.platform.service.TrialReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Trial 周报/月报调度 · V2.0.78.1（主站 D-5 Day 2）
 *
 * 主人 09-13 14:25 拍板：④ 周报/月报发送方 → A 主站代发
 *
 * 触发节奏：
 * - 周报：每周一 09:00
 * - 月报：每月 1 号 09:00
 *
 * 触发逻辑：
 * - 周报：所有 TrialHealthScore.band ∈ {HEALTHY, WATCH} 的 trial 发周报
 * - 月报：所有 TrialHealthScore 不区分 band 都发（含不活跃提醒）
 *
 * 关联：TrialReportService.sendWeeklyReport / sendMonthlyReport
 */
@Component
public class TrialReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialReportScheduler.class);

    @Autowired private TrialRegistrationRepository trialRepo;
    @Autowired private TrialHealthScoreRepository scoreRepo;
    @Autowired private TrialHealthScoreService healthScoreService;
    @Autowired private TrialReportService reportService;

    /**
     * V2.0.78.1：每周一 09:00 给 HEALTHY+WATCH 的 trial 发周报
     * cron = "0 0 9 ? * MON"（周一 09:00:00）
     */
    @Scheduled(cron = "0 0 9 ? * MON")
    public void sendWeeklyReports() {
        // 先触发一次 score 跑批（保证最新）
        healthScoreService.recomputeAll();

        List<TrialHealthScore> candidates = scoreRepo.findAll().stream()
                .filter(s -> s.getBand() == TrialHealthScore.Band.HEALTHY
                          || s.getBand() == TrialHealthScore.Band.WATCH)
                .toList();

        log.info("[TrialReport] weekly: candidates={}", candidates.size());
        int sent = 0;
        for (TrialHealthScore s : candidates) {
            TrialRegistration reg = trialRepo.findById(s.getRegistrationId()).orElse(null);
            if (reg == null || reg.getStatus() != TrialRegistration.Status.ACTIVATED) continue;
            try {
                reportService.sendWeeklyReport(reg.getId(), s);
                sent++;
            } catch (Exception e) {
                log.error("[TrialReport] weekly regId={} failed", reg.getId(), e);
            }
        }
        log.info("[TrialReport] weekly sent={} / total={}", sent, candidates.size());
    }

    /**
     * V2.0.78.1：每月 1 号 09:00 给所有 ACTIVATED trial 发月报（含不活跃提醒）
     * cron = "0 0 9 1 * *"（每月 1 号 09:00:00）
     */
    @Scheduled(cron = "0 0 9 1 * *")
    public void sendMonthlyReports() {
        // 先触发一次 score 跑批
        healthScoreService.recomputeAll();

        List<TrialRegistration> allActive = trialRepo.findByStatus(TrialRegistration.Status.ACTIVATED);

        log.info("[TrialReport] monthly: candidates={}", allActive.size());
        int sent = 0;
        for (TrialRegistration reg : allActive) {
            TrialHealthScore s = scoreRepo.findByRegistrationId(reg.getId()).orElse(null);
            try {
                reportService.sendMonthlyReport(reg.getId(), s);
                sent++;
            } catch (Exception e) {
                log.error("[TrialReport] monthly regId={} failed", reg.getId(), e);
            }
        }
        log.info("[TrialReport] monthly sent={} / total={}", sent, allActive.size());
    }
}
