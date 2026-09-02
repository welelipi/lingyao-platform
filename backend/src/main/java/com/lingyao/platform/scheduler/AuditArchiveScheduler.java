package com.lingyao.platform.scheduler;

import com.lingyao.platform.entity.AuthAuditEvent;
import com.lingyao.platform.entity.CompanyAuditLog;
import com.lingyao.platform.entity.CompanyAuditLogArchive;
import com.lingyao.platform.repository.AuthAuditEventRepository;
import com.lingyao.platform.repository.CompanyAuditLogArchiveRepository;
import com.lingyao.platform.repository.CompanyAuditLogRepository;
import com.lingyao.platform.service.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计归档 + 安全告警 Scheduler — V2.0.42 P2.4
 *
 * 三个任务：
 * 1) 归档（每日 02:30）：company_audit_log 中 N 天前（默认 90）记录 → company_audit_log_archive 归档表，原表删除
 * 2) 安全事件清理（每日 02:35）：auth_audit_event 中 N 天前（默认 90，V2.0.26 设计值）记录删除（安全事件不做归档镜像，量小且聚合统计已留存）
 * 3) 安全告警（每 10 分钟）：LOGIN_FAIL 单 IP ≥5 次 / TOKEN_REFRESH_REPLAY ≥1 次 → 邮件告警到超管邮箱（EMAIL 通道）
 *
 * 依赖：@EnableScheduling 已在 LingyaoApplication 启用（V2.0.10）
 */
@Component
public class AuditArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveScheduler.class);
    private static final int BATCH_SIZE = 500;

    @Autowired private CompanyAuditLogRepository auditRepo;
    @Autowired private CompanyAuditLogArchiveRepository archiveRepo;
    @Autowired private AuthAuditEventRepository authAuditRepo;
    @Autowired private PushService pushService;

    @Value("${lingyao.audit.archive-days:90}")
    private int archiveDays;

    // ═══════════════════════ 1) 业务审计归档 ═══════════════════════

    /** 每日 02:30 归档（dev 可经 /api/_diag/audit-archive 手动触发） */
    @Scheduled(cron = "0 30 2 * * *")
    @Transactional
    public void archiveOldAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(archiveDays);
        List<CompanyAuditLog> old = auditRepo.findByCreatedAtBefore(cutoff);
        if (old.isEmpty()) {
            log.info("[AuditArchive] 无需归档（cutoff={}）", cutoff);
            return;
        }
        int total = old.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<CompanyAuditLog> batch = old.subList(i, Math.min(i + BATCH_SIZE, total));
            for (CompanyAuditLog logRow : batch) {
                CompanyAuditLogArchive arch = new CompanyAuditLogArchive();
                arch.setCompanyId(logRow.getCompanyId());
                arch.setActorUserId(logRow.getActorUserId());
                arch.setActorUsername(logRow.getActorUsername());
                arch.setAction(logRow.getAction());
                arch.setResourceType(logRow.getResourceType());
                arch.setResourceId(logRow.getResourceId());
                arch.setSummary(logRow.getSummary());
                arch.setIpAddress(logRow.getIpAddress());
                arch.setCreatedAt(logRow.getCreatedAt());
                archiveRepo.save(arch);
            }
            auditRepo.deleteAll(batch);
        }
        log.info("[AuditArchive] ✅ 归档完成：{} 条（cutoff={}）", total, cutoff);
    }

    // ═══════════════════════ 2) 安全事件清理 ═══════════════════════

    /** 每日 02:35 清理 N 天前安全事件（V2.0.26 设计保留期 = 90 天） */
    @Scheduled(cron = "0 35 2 * * *")
    @Transactional
    public void purgeOldAuthAuditEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(archiveDays);
        long deleted = authAuditRepo.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[AuditArchive] 清理过期安全事件：{} 条（cutoff={}）", deleted, cutoff);
        }
    }

    // ═══════════════════════ 3) 安全告警 ═══════════════════════

    /** 每 10 分钟扫描安全异常 → 邮件告警超管 */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void scanSecurityAlerts() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(10);

        // 告警 1：重放攻击检测（refresh_token replay，任何一次都值得知道）
        long replays = authAuditRepo.countByEventTypeAndCreatedAtAfter(
                AuthAuditEvent.EventType.TOKEN_REFRESH_REPLAY, since);
        if (replays > 0) {
            sendAlert("🚨 [凌瑶安全告警] Token 重放攻击",
                    "最近 10 分钟检测到 <b>" + replays + "</b> 次 refresh_token 重放（TOKEN_REFRESH_REPLAY）。<br>"
                            + "可能为攻击者窃取 token 后复用，已全部拒绝。建议排查相关 IP 登录行为。");
        }

        // 告警 2：登录失败暴增（≥5 次/10 分钟）
        long loginFails = authAuditRepo.countByEventTypeAndCreatedAtAfterAndSuccessFalse(
                AuthAuditEvent.EventType.LOGIN_FAIL, since);
        if (loginFails >= 5) {
            sendAlert("🚨 [凌瑶安全告警] 登录失败暴增",
                    "最近 10 分钟检测到 <b>" + loginFails + "</b> 次登录失败（LOGIN_FAIL）。<br>"
                            + "可能为暴力破解尝试。若持续暴增请检查 RateLimit 与 IP 黑名单。");
        }

        // 告警 3：账号锁定
        long lockouts = authAuditRepo.countByEventTypeAndCreatedAtAfter(
                AuthAuditEvent.EventType.LOGIN_LOCKED, since);
        if (lockouts > 0) {
            sendAlert("🚨 [凌瑶安全告警] 账号被锁定",
                    "最近 10 分钟检测到 <b>" + lockouts + "</b> 次账号锁定（LOGIN_LOCKED）。<br>"
                            + "连续失败触发锁定保护，请确认是否为本人操作。");
        }
    }

    private void sendAlert(String subject, String htmlBody) {
        String to = pushService.firstActiveEmailRecipient();
        if (to == null) {
            log.warn("[AuditArchive] 告警未发送（无激活 EMAIL 通道）：{}", subject);
            return;
        }
        boolean sent = pushService.sendGenericHtmlMail(to, subject, htmlBody);
        if (sent) {
            log.info("[AuditArchive] ✅ 安全告警已发送：{} → {}", subject, to);
        } else {
            log.warn("[AuditArchive] 告警发送失败（SMTP 未配置）：{}", subject);
        }
    }
}
