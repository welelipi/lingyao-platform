package com.lingyao.platform.service;

import com.lingyao.platform.config.NotifyEmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2.0.69：邮箱验证码服务（A+D+B 邮件版安全方案核心）
 *
 * 用途：
 * - D. 新设备/异地登录 challenge（验证通过发 deviceTrustTicket）
 * - B. 敏感操作二次验证（改密码 / 管理员建号）
 * - A. 异常告警邮件复用本类 sendMail（节流由 SecurityAlertService 控制）
 *
 * 设计口径（对齐 CaptchaService 风格）：
 * - 6 位数字码，SecureRandom 生成
 * - 10 分钟有效，验证成功即作废（一次性）
 * - 同一 purpose+目标 连续错 5 次锁 15 分钟（防爆破）
 * - 同一 purpose+目标 发码频控 60 秒（防骚扰/防邮件轰炸）
 * - 内存 ConcurrentHashMap 存储（重启失效=天然清零，量级足够）
 * - dev 模式（lingyao.notify.email.enabled=false）不真发邮件，验证码打 WARN 日志（E2E 可取）
 */
@Service
public class EmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpService.class);

    /** 验证码有效期（分钟） */
    private static final int OTP_TTL_MINUTES = 10;
    /** 最大错误尝试次数 */
    private static final int MAX_ATTEMPTS = 5;
    /** 错误锁定时长（分钟） */
    private static final int LOCK_MINUTES = 15;
    /** 发码频控（秒） */
    private static final int SEND_INTERVAL_SECONDS = 60;

    private final SecureRandom random = new SecureRandom();

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired
    private NotifyEmailProperties emailProps;

    /** OTP 记录：key = purpose + "|" + 目标（用户名或邮箱），value = 码/过期/错误数/锁定时间 */
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    /** 上次发码时间（频控）：key 同上 */
    private final Map<String, LocalDateTime> lastSentAt = new ConcurrentHashMap<>();

    /** purpose 常量 */
    public static final String PURPOSE_DEVICE_LOGIN = "DEVICE_LOGIN";   // D：新设备登录
    public static final String PURPOSE_CHANGE_PASSWORD = "CHANGE_PWD";  // B：改密码
    public static final String PURPOSE_ADMIN_CREATE_USER = "ADMIN_CU";  // B：管理员建号

    private record OtpEntry(String code, LocalDateTime expiresAt, int failedAttempts, LocalDateTime lockedUntil) {}

    /**
     * 发送验证码到指定邮箱
     * @return ok=true 发送成功（或 dev 模式记日志）；message 提示；devCode=dev 模式下的验证码（生产为 null）
     */
    public Map<String, Object> sendOtp(String purpose, String username, String toEmail) {
        if (toEmail == null || toEmail.isBlank() || !toEmail.contains("@")) {
            return Map.of("ok", false, "message", "该账号未绑定邮箱，无法发送验证码");
        }
        String key = purpose + "|" + username;

        // 频控：60 秒内不重发
        LocalDateTime last = lastSentAt.get(key);
        if (last != null && last.plusSeconds(SEND_INTERVAL_SECONDS).isAfter(LocalDateTime.now())) {
            long waitSec = java.time.Duration.between(LocalDateTime.now(), last.plusSeconds(SEND_INTERVAL_SECONDS)).getSeconds();
            return Map.of("ok", false, "message", "发送过于频繁，请 " + Math.max(waitSec, 1) + " 秒后再试");
        }

        // 锁定检查
        OtpEntry existing = otpStore.get(key);
        if (existing != null && existing.lockedUntil() != null && existing.lockedUntil().isAfter(LocalDateTime.now())) {
            return Map.of("ok", false, "message", "错误次数过多，已锁定，请 " + LOCK_MINUTES + " 分钟后再试");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        otpStore.put(key, new OtpEntry(code, LocalDateTime.now().plusMinutes(OTP_TTL_MINUTES), 0, null));
        lastSentAt.put(key, LocalDateTime.now());

        boolean devMode = (mailSender == null) || !emailProps.isEnabled();
        if (devMode) {
            log.warn("📧 [DEV 模式] 邮箱验证码未真实发送。purpose={}, user={}, to={}, code={}", purpose, username, toEmail, code);
            return Map.of("ok", true, "message", "验证码已生成（DEV 模式见后端日志）", "devCode", code);
        }
        try {
            sendOtpMail(toEmail, code, purpose);
            // E2E 钩子：dev/测试环境日志含完整验证码供自动化取用；生产环境建议 logging 屏蔽本 logger
            log.info("📧 邮箱验证码已发送：purpose={}, user={}, to={}, code={}", purpose, username, toEmail, code);
            return Map.of("ok", true, "message", "验证码已发送至邮箱 " + maskEmail(toEmail));
        } catch (Exception e) {
            log.error("📧 邮箱验证码发送失败：purpose={}, user={}, to={}, error={}", purpose, username, toEmail, e.getMessage(), e);
            return Map.of("ok", false, "message", "验证码邮件发送失败，请稍后重试");
        }
    }

    /**
     * 校验验证码
     * @return null=通过；非 null=失败原因文案
     */
    public String verifyOtp(String purpose, String username, String code) {
        if (code == null || code.isBlank()) return "请输入邮箱验证码";
        String key = purpose + "|" + username;
        OtpEntry entry = otpStore.get(key);
        if (entry == null) return "请先获取邮箱验证码";
        if (entry.lockedUntil() != null && entry.lockedUntil().isAfter(LocalDateTime.now())) {
            return "错误次数过多已锁定，请稍后再试";
        }
        if (entry.expiresAt().isBefore(LocalDateTime.now())) {
            otpStore.remove(key);
            return "验证码已过期，请重新获取";
        }
        if (!entry.code().equals(code.trim())) {
            int failed = entry.failedAttempts() + 1;
            if (failed >= MAX_ATTEMPTS) {
                otpStore.put(key, new OtpEntry(entry.code(), entry.expiresAt(), failed,
                        LocalDateTime.now().plusMinutes(LOCK_MINUTES)));
                log.warn("📧 邮箱验证码连续错误 {} 次，锁定 {} 分钟：purpose={}, user={}", failed, LOCK_MINUTES, purpose, username);
                return "验证码错误次数过多，已锁定 " + LOCK_MINUTES + " 分钟";
            }
            otpStore.put(key, new OtpEntry(entry.code(), entry.expiresAt(), failed, null));
            return "验证码错误，剩余尝试次数 " + (MAX_ATTEMPTS - failed);
        }
        // 通过 → 一次性作废
        otpStore.remove(key);
        lastSentAt.remove(key);
        return null;
    }

    /** A：通用告警/通知邮件（SecurityAlertService 用） */
    public boolean sendMail(String to, String subject, String html) {
        if (mailSender == null || !emailProps.isEnabled()) {
            log.warn("📧 [DEV 模式] 邮件未真实发送。to={}, subject={}", to, subject);
            return false;
        }
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(emailProps.getFrom() != null && !emailProps.getFrom().isBlank()
                    ? emailProps.getFrom() : emailProps.getUsername());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("📧 邮件发送失败：to={}, subject={}, error={}", to, subject, e.getMessage(), e);
            return false;
        }
    }

    private void sendOtpMail(String to, String code, String purpose) throws Exception {
        String scene = switch (purpose) {
            case PURPOSE_DEVICE_LOGIN -> "新设备登录验证";
            case PURPOSE_CHANGE_PASSWORD -> "修改密码验证";
            case PURPOSE_ADMIN_CREATE_USER -> "创建账号验证";
            default -> "身份验证";
        };
        String html = String.format("""
                <div style="max-width:520px;margin:0 auto;font-family:'PingFang SC','Microsoft YaHei',sans-serif;color:#1e293b;">
                  <h2 style="color:#0A1F3D;">凌瑶智数 · %s</h2>
                  <p>您好，本次操作的验证码为：</p>
                  <div style="font-size:32px;font-weight:bold;letter-spacing:8px;color:#0A1F3D;background:#F1F5F9;padding:16px 24px;text-align:center;border-radius:8px;">%s</div>
                  <p style="color:#64748B;font-size:13px;">验证码 %d 分钟内有效。若非您本人操作，请忽略本邮件并建议立即修改密码。</p>
                  <p style="color:#94A3B8;font-size:12px;">凌瑶智数平台 · 系统邮件，请勿回复</p>
                </div>
                """, scene, code, OTP_TTL_MINUTES);
        var message = mailSender.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(emailProps.getFrom() != null && !emailProps.getFrom().isBlank()
                ? emailProps.getFrom() : emailProps.getUsername());
        helper.setTo(to);
        helper.setSubject("【凌瑶智数】" + scene + "验证码：" + code);
        helper.setText(html, true);
        mailSender.send(message);
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}
