package com.lingyao.platform.service;

import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.repository.AuthAuditEventRepository;
import com.lingyao.platform.repository.SysUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2.0.69 A：异常登录告警（零登录摩擦——只在出事时通知）
 *
 * 规则：
 * 1. 暴力破解检测：同一 IP 1 小时内登录失败 ≥ 10 次 → 邮件告警平台超管（每 IP 每小时最多 1 封，节流）
 * 2. 新设备登录通知：用户通过新设备邮箱码验证登录成功 → 给用户本人邮箱发通知（知会性质）
 *
 * 复用 auth_audit_event 现有数据（V2.0.61 起 LOGIN_FAIL 均已落库），无需新表。
 */
@Service
public class SecurityAlertService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAlertService.class);

    /** 1 小时窗口内同 IP 失败阈值 */
    private static final int BRUTE_FORCE_THRESHOLD = 10;
    /** 同 IP 告警节流（分钟） */
    private static final int ALERT_THROTTLE_MINUTES = 60;

    private final Map<String, LocalDateTime> lastAlertAt = new ConcurrentHashMap<>();

    @Autowired
    private AuthAuditEventRepository authAuditEventRepository;

    @Autowired
    private SysUserRepository userRepo;

    @Autowired
    private EmailOtpService emailOtpService;

    @Autowired
    private PushService pushService;

    /**
     * A1：登录失败后调用——检查同 IP 1 小时失败数，超阈值发管理员告警（异步、异常不外抛）
     * 由 AuthService.login 失败路径调用
     */
    @Async
    public void checkBruteForceAsync(String ip, String username) {
        try {
            if (ip == null || ip.isBlank()) return;
            LocalDateTime since = LocalDateTime.now().minusHours(1);
            long fails = authAuditEventRepository.countRecentFailuresByIp(ip, since);
            if (fails < BRUTE_FORCE_THRESHOLD) return;

            LocalDateTime last = lastAlertAt.get(ip);
            if (last != null && last.plusMinutes(ALERT_THROTTLE_MINUTES).isAfter(LocalDateTime.now())) {
                return; // 节流：同 IP 1 小时最多 1 封
            }
            lastAlertAt.put(ip, LocalDateTime.now());

            String subject = "【凌瑶智数】安全告警：检测到疑似暴力破解";
            String html = String.format("""
                    <div style="max-width:560px;margin:0 auto;font-family:'PingFang SC','Microsoft YaHei',sans-serif;color:#1e293b;">
                      <h2 style="color:#B91C1C;">⚠️ 安全告警</h2>
                      <p>检测到疑似暴力破解行为：</p>
                      <table border="1" cellpadding="8" cellspacing="0" style="border-collapse:collapse;font-size:14px;">
                        <tr><td>来源 IP</td><td><strong>%s</strong></td></tr>
                        <tr><td>近 1 小时失败次数</td><td><strong>%d</strong> 次（阈值 %d）</td></tr>
                        <tr><td>最近尝试账号</td><td>%s</td></tr>
                        <tr><td>时间</td><td>%s</td></tr>
                      </table>
                      <p style="color:#64748B;font-size:13px;">已由登录锁定 + 图形验证码 + 限流拦截。可在管理后台「安全事件」查看详情；如确认攻击，建议在防火墙封禁该 IP。</p>
                    </div>
                    """, ip, fails, BRUTE_FORCE_THRESHOLD, username == null ? "-" : username, LocalDateTime.now());

            // 收件人：所有平台超管邮箱 + EMAIL 通知通道（超管邮箱兜底）
            boolean sentAny = false;
            for (SysUser admin : userRepo.findByIsPlatformAdminTrue()) {
                if (admin.getEmail() != null && admin.getEmail().contains("@")) {
                    sentAny |= emailOtpService.sendMail(admin.getEmail(), subject, html);
                }
            }
            String fallback = pushService.firstActiveEmailRecipient();
            if (!sentAny && fallback != null) {
                emailOtpService.sendMail(fallback, subject, html);
            }
            log.warn("🚨 [SecurityAlert] 暴力破解告警已触发：ip={}, fails1h={}", ip, fails);
        } catch (Exception e) {
            log.error("[SecurityAlert] 告警检查异常（不影响登录主流程）：{}", e.getMessage(), e);
        }
    }

    /**
     * A2：新设备验证登录成功后调用——给用户本人发知会邮件（异步）
     */
    @Async
    public void notifyNewDeviceLoginAsync(Long userId, String ip, String userAgent) {
        try {
            SysUser user = userRepo.findById(userId).orElse(null);
            if (user == null || user.getEmail() == null || !user.getEmail().contains("@")) return;
            String ua = userAgent == null ? "-" : (userAgent.length() > 100 ? userAgent.substring(0, 100) : userAgent);
            String subject = "【凌瑶智数】新设备登录提醒";
            String html = String.format("""
                    <div style="max-width:560px;margin:0 auto;font-family:'PingFang SC','Microsoft YaHei',sans-serif;color:#1e293b;">
                      <h2 style="color:#0A1F3D;">新设备登录提醒</h2>
                      <p>您的账号 <strong>%s</strong> 刚刚在一台新设备上通过邮箱验证码登录：</p>
                      <table border="1" cellpadding="8" cellspacing="0" style="border-collapse:collapse;font-size:14px;">
                        <tr><td>时间</td><td>%s</td></tr>
                        <tr><td>IP</td><td>%s</td></tr>
                        <tr><td>设备</td><td>%s</td></tr>
                      </table>
                      <p style="color:#64748B;font-size:13px;">该设备 30 天内再次登录将免验证。若这不是您本人的操作，请立即修改密码并联系管理员。</p>
                    </div>
                    """, user.getUsername(), LocalDateTime.now(), ip, ua);
            emailOtpService.sendMail(user.getEmail(), subject, html);
            log.info("📧 [SecurityAlert] 新设备登录通知已发：user={}", user.getUsername());
        } catch (Exception e) {
            log.error("[SecurityAlert] 新设备通知异常（不影响登录主流程）：{}", e.getMessage(), e);
        }
    }
}
