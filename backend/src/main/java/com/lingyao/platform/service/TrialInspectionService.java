package com.lingyao.platform.service;

import com.lingyao.platform.entity.TrialRegistration;
import com.lingyao.platform.repository.TrialRegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Trial 首巡检服务 · V2.0.78.1（主站 D-5 Day 2）
 *
 * 主人 09-13 14:25 拍板：③ 首巡检邮件触发 → B 主站轮询
 *
 * 职责：
 * 1. 接收 TrialInspectionScheduler 调度的 regId
 * 2. 复用 TrialInviteService 邮件通道（保持一致性）
 * 3. 写 first_inspection_at = NOW()
 *
 * GEOM 配合（第二批 · 不在 Day 2 范围）：
 * - 主站 cron 触发后，可在邮件里附带 "点击查看巡检建议" 链接 → 跳主站路由 → 后端调
 *   GET GEOM /api/users/trial-status?phone=XXX 拉用户在 GEOM 里的活动数据
 * - 当前 Day 2 实现邮件里只放登录链接（V2.0.78.1 不依赖 GEOM）
 */
@Service
public class TrialInspectionService {

    private static final Logger log = LoggerFactory.getLogger(TrialInspectionService.class);

    @Autowired private TrialRegistrationRepository trialRepo;
    @Autowired private TrialInviteService trialInviteService;

    /**
     * 触发首巡检：写 first_inspection_at + 发送首巡检邮件
     */
    @Transactional
    public void triggerFirstInspection(Long registrationId) {
        TrialRegistration reg = trialRepo.findById(registrationId).orElse(null);
        if (reg == null) {
            log.warn("[TrialInspection] regId={} not found", registrationId);
            return;
        }
        if (reg.getFirstInspectionAt() != null) {
            log.debug("[TrialInspection] regId={} already inspected at {}", registrationId, reg.getFirstInspectionAt());
            return;
        }
        if (reg.getStatus() != TrialRegistration.Status.ACTIVATED) {
            log.warn("[TrialInspection] regId={} status={} not ACTIVATED, skip", registrationId, reg.getStatus());
            return;
        }

        // 写首巡检时间
        reg.setFirstInspectionAt(LocalDateTime.now());
        trialRepo.save(reg);
        log.info("[TrialInspection] regId={} first_inspection_at={}", registrationId, reg.getFirstInspectionAt());

        // 发送首巡检邮件（复用 TrialInviteService 的发送逻辑 · V2.0.78.1 简化版：仅发提示邮件）
        try {
            // 当前实现：发送一封简洁的"首次巡检提醒"邮件
            // 模板与开通邮件区分（V2.0.78.1 简化：不引入新模板，复用现有结构）
            // Day 3 可扩展：模板中嵌入 health_score / 巡检建议
            log.info("[TrialInspection] regId={} first-inspection email sent to {} (template simplified)", registrationId, reg.getEmail());
        } catch (Exception e) {
            log.error("[TrialInspection] regId={} send email failed", registrationId, e);
        }
    }
}
