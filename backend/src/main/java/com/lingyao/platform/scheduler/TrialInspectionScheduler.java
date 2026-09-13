package com.lingyao.platform.scheduler;

import com.lingyao.platform.entity.TrialRegistration;
import com.lingyao.platform.repository.TrialRegistrationRepository;
import com.lingyao.platform.service.TrialInspectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trial 首巡检邮件触发调度 · V2.0.78.1（主站 D-5 Day 2）
 *
 * 主人 09-13 14:25 拍板：③ 首巡检邮件触发 → B 主站轮询（推荐）
 *
 * 触发逻辑：每 6h 扫表
 * - 条件：trial_registration.status = ACTIVATED
 *        AND first_inspection_at IS NULL
 *        AND created_at < NOW() - 6h（注册满 6h 仍未巡检的用户）
 * - 动作：调用 TrialInspectionService.triggerFirstInspection(regId)
 *
 * 与 V2.0.78.1 TrialInspectionService 配合：
 * - 发送首巡检邮件（带 GEOM 等产品入口链接 + 巡检状态查询链接）
 * - 写 first_inspection_at = NOW()
 *
 * 依赖：@EnableScheduling 已在 LingyaoApplication 启用（V2.0.10）
 */
@Component
public class TrialInspectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialInspectionScheduler.class);

    @Autowired private TrialRegistrationRepository trialRepo;
    @Autowired private TrialInspectionService inspectionService;

    /**
     * V2.0.78.1：每 6h 触发一次首巡检邮件
     * cron = "0 0 0/6 * * *" 表示每小时 0 分起每 6 小时一次（0/6/12/18 点）
     */
    @Scheduled(cron = "0 0 0/6 * * *")
    public void triggerPendingFirstInspections() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(6);
        List<TrialRegistration> pending = trialRepo.findByStatus(TrialRegistration.Status.ACTIVATED)
                .stream()
                .filter(r -> r.getFirstInspectionAt() == null)
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isBefore(cutoff))
                .toList();

        if (pending.isEmpty()) {
            log.debug("[TrialInspection] no pending first-inspection trials");
            return;
        }

        log.info("[TrialInspection] triggering first-inspection for {} trials", pending.size());
        int triggered = 0;
        for (TrialRegistration reg : pending) {
            try {
                inspectionService.triggerFirstInspection(reg.getId());
                triggered++;
            } catch (Exception e) {
                log.error("[TrialInspection] trigger failed regId={}", reg.getId(), e);
            }
        }
        log.info("[TrialInspection] triggered={} / total={}", triggered, pending.size());
    }
}
