package com.lingyao.platform.service;

import com.lingyao.platform.entity.Company;
import com.lingyao.platform.entity.CompanyUser;
import com.lingyao.platform.entity.LicenseReminderLog;
import com.lingyao.platform.entity.NotificationChannel;
import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.repository.CompanyRepository;
import com.lingyao.platform.repository.CompanyUserRepository;
import com.lingyao.platform.repository.LicenseReminderLogRepository;
import com.lingyao.platform.repository.NotificationChannelRepository;
import com.lingyao.platform.repository.SysUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 许可证过期提醒服务 — V2.0.10
 *
 * 职责：
 * 1. 提前 30/15/7/1 天提醒大超管 + 公司超管（双发）
 * 2. 过期当天自动 ACTIVE → EXPIRED
 * 3. 提醒去重（同公司同类型同日只发一次）
 *
 * 触发：
 * - LicenseExpirationScheduler.morningCheck() 每日 9:00
 * - LicenseExpirationScheduler.eveningExpire() 每日 23:00
 */
@Service
public class LicenseExpirationService {

    private static final Logger log = LoggerFactory.getLogger(LicenseExpirationService.class);

    /** 提醒提前天数（30/15/7/1） */
    private static final int[] REMIND_DAYS_BEFORE = {30, 15, 7, 1};

    @Autowired private CompanyRepository companyRepo;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private SysUserRepository userRepo;
    @Autowired private LicenseReminderLogRepository reminderLogRepo;
    @Autowired private NotificationChannelRepository channelRepo;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 早 9:00 cron：检查即将过期的公司，提前 30/15/7/1 天通知
     */
    @Transactional
    public void morningCheck() {
        log.info("🔔 许可证过期提醒早查开始（每日 9:00）");

        LocalDateTime now = LocalDateTime.now();
        for (int daysBefore : REMIND_DAYS_BEFORE) {
            // 当天 00:00 - 23:59:59 区间内 licenseEnd 落在这里的公司
            LocalDateTime dayStart = now.plusDays(daysBefore).toLocalDate().atStartOfDay();
            LocalDateTime dayEnd = dayStart.plusDays(1).minusSeconds(1);

            List<Company> expiring = companyRepo.findByStatusAndLicenseEndBetween(
                    Company.CompanyStatus.ACTIVE, dayStart, dayEnd);

            for (Company company : expiring) {
                try {
                    remindCompany(company, daysBefore);
                } catch (Exception e) {
                    log.error("❌ 提醒失败：company={}, daysBefore={}, error={}",
                            company.getName(), daysBefore, e.getMessage(), e);
                }
            }
        }
        log.info("🔔 许可证过期提醒早查完成");
    }

    /**
     * 晚 23:00 cron：把所有 licenseEnd 已过的 ACTIVE 公司自动改为 EXPIRED
     */
    @Transactional
    public void eveningExpire() {
        log.info("🔔 许可证过期晚处理开始（每日 23:00）");

        LocalDateTime now = LocalDateTime.now();
        List<Company> expired = companyRepo.findByStatusAndLicenseEndBefore(
                Company.CompanyStatus.ACTIVE, now);

        for (Company company : expired) {
            try {
                company.setStatus(Company.CompanyStatus.EXPIRED);
                companyRepo.save(company);
                log.info("🔔 公司 {} ({}) 自动改为 EXPIRED（licenseEnd={}）",
                        company.getName(), company.getCode(), company.getLicenseEnd());
                // 推送 EXPIRED 通知（给大超管 + 公司超管）
                sendExpiredNotice(company);
            } catch (Exception e) {
                log.error("❌ 过期处理失败：company={}, error={}",
                        company.getName(), e.getMessage(), e);
            }
        }
        log.info("🔔 许可证过期晚处理完成，共 {} 家公司转 EXPIRED", expired.size());
    }

    /**
     * 提醒某公司即将过期（双发：大超管 + 公司超管）
     */
    private void remindCompany(Company company, int daysBefore) {
        // 去重：今天是否已发过
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1).minusSeconds(1);
        List<LicenseReminderLog> existing = reminderLogRepo
                .findByCompanyIdAndReminderTypeAndDaysBeforeAndSentAtBetween(
                        company.getId(),
                        LicenseReminderLog.ReminderType.EXPIRING,
                        daysBefore,
                        startOfDay,
                        endOfDay);
        if (!existing.isEmpty()) {
            log.info("⏭ 跳过：company={}, daysBefore={}，今日已提醒过",
                    company.getName(), daysBefore);
            return;
        }

        // 收件人：大超管 + 公司超管
        List<String> recipients = collectRecipients(company);
        if (recipients.isEmpty()) {
            log.warn("⚠️ 公司 {} 无收件人邮箱/企微 id", company.getName());
            return;
        }

        // 推送通知（飞书 webhook + 邮件双通道）
        String content = buildExpiringContent(company, daysBefore);
        boolean ok = sendToWecomBot(content);   // 推送到企微群
        recordReminder(company, LicenseReminderLog.ReminderType.EXPIRING, daysBefore,
                String.join(",", recipients), "WECHAT_WORK", ok, ok ? null : "WECHAT_WORK push failed");
    }

    /**
     * 给大超管 + 公司超管推 EXPIRED 通知
     */
    private void sendExpiredNotice(Company company) {
        List<String> recipients = collectRecipients(company);
        if (recipients.isEmpty()) return;
        String content = buildExpiredContent(company);
        boolean ok = sendToWecomBot(content);
        recordReminder(company, LicenseReminderLog.ReminderType.EXPIRED, null,
                String.join(",", recipients), "WECHAT_WORK", ok, ok ? null : "WECHAT_WORK push failed");
    }

    /**
     * 收集收件人：所有 platform_admin（不一定绑这家公司） + 公司超管
     */
    private List<String> collectRecipients(Company company) {
        List<String> out = new ArrayList<>();
        // 1) 所有 platform_admin
        for (SysUser admin : userRepo.findByIsPlatformAdminTrue()) {
            if (admin.getEmail() != null && !admin.getEmail().isEmpty()) {
                out.add(admin.getEmail());
            }
        }
        // 2) 该公司的 SUPER_ADMIN
        List<CompanyUser> companyAdmins = companyUserRepo
                .findByCompanyIdAndRole(company.getId(), CompanyUser.CompanyRole.SUPER_ADMIN);
        for (CompanyUser cu : companyAdmins) {
            userRepo.findById(cu.getUserId()).ifPresent(u -> {
                if (u.getEmail() != null && !u.getEmail().isEmpty()) {
                    out.add(u.getEmail());
                }
            });
        }
        return out;
    }

    private void recordReminder(Company company,
                                LicenseReminderLog.ReminderType type,
                                Integer daysBefore,
                                String sentTo,
                                String channel,
                                boolean success,
                                String errorMessage) {
        LicenseReminderLog log = new LicenseReminderLog();
        log.setCompanyId(company.getId());
        log.setReminderType(type);
        log.setDaysBefore(daysBefore);
        log.setSentTo(sentTo);
        log.setChannel(channel);
        log.setSentAt(LocalDateTime.now());
        log.setSuccess(success);
        log.setErrorMessage(errorMessage);
        reminderLogRepo.save(log);
    }

    private boolean sendToWecomBot(String content) {
        List<NotificationChannel> channels = channelRepo.findByChannelTypeAndStatus(
                NotificationChannel.ChannelType.WECHAT_WORK, NotificationChannel.Status.ACTIVE);
        if (channels.isEmpty()) {
            log.warn("⚠️ 未配置任何激活的企微群通道");
            return false;
        }
        boolean allOk = true;
        for (NotificationChannel ch : channels) {
            try {
                String json = String.format(
                        "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
                        content.replace("\"", "\\\"").replace("\n", "\\n"));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(ch.getWebhookUrl()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 300) {
                    log.error("❌ 推送失败：channel={}, HTTP {}", ch.getName(), resp.statusCode());
                    allOk = false;
                }
            } catch (Exception e) {
                log.error("❌ 推送异常：channel={}, error={}", ch.getName(), e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }

    private String buildExpiringContent(Company company, int daysBefore) {
        long totalDays = company.getLicenseStart() != null && company.getLicenseEnd() != null
                ? ChronoUnit.DAYS.between(company.getLicenseStart(), company.getLicenseEnd())
                : -1;
        return String.format(
                "【许可证即将到期提醒】\n公司: %s (%s)\n到期日期: %s\n剩余天数: %d 天\n\n请大超管尽快处理：\n1. 续期：编辑公司 → 修改截止日期\n2. 暂停：编辑公司 → 状态改为「已暂停」\n3. 删除：编辑公司 → 状态改为「已删除」\n\n——凌瑶智数自动通知",
                company.getName(),
                company.getCode(),
                company.getLicenseEnd(),
                daysBefore);
    }

    private String buildExpiredContent(Company company) {
        return String.format(
                "【许可证已过期通知】\n公司: %s (%s)\n过期日期: %s\n\n公司状态已自动改为「已过期」，公司超管和操作员登录会被拦截。\n\n请大超管尽快处理：\n1. 续期：编辑公司 → 修改截止日期 → 状态改「已开通」\n2. 暂停/删除：编辑公司 → 修改状态\n\n——凌瑶智数自动通知",
                company.getName(),
                company.getCode(),
                company.getLicenseEnd());
    }
}