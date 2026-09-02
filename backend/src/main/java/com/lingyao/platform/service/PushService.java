package com.lingyao.platform.service;

import com.lingyao.platform.config.NotifyEmailProperties;
import com.lingyao.platform.entity.NotificationChannel;
import com.lingyao.platform.entity.Registration;
import com.lingyao.platform.repository.NotificationChannelRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 推送服务 — Bug-03 修复（增加告警）+ V2.0.27 Bug-21（EMAIL 通道）
 *
 * 支持通道：
 * - WECHAT_WORK：企微群机器人 Webhook
 * - FEISHU：飞书群机器人 Webhook（消息体兼容企微格式）
 * - EMAIL：内置邮箱推送（webhook_url 字段存收件邮箱，如超管邮箱 188407408@qq.com）
 *   - lingyao.notify.email.enabled=false（dev 默认）→ 只记日志不发真邮件
 *
 * 推送失败时记录 ERROR 日志，不阻塞报名主流程
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    @Autowired
    private NotificationChannelRepository channelRepo;

    @Autowired
    private NotifyEmailProperties emailProps;

    /** mail starter 在 classpath 时注入；否则保持 null（EMAIL 通道降级为记日志） */
    @Autowired(required = false)
    private JavaMailSender mailSender;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 异步推送报名通知 — V2.0.27：同时扫 WECHAT_WORK / FEISHU / EMAIL 三类激活通道
     */
    @Async
    public void pushRegistrationAsync(Registration reg) {
        log.info("准备推送报名通知：id={}, name={}", reg.getId(), reg.getName());
        String content = buildRegistrationContent(reg);

        // 1) 企微群机器人
        pushWebhook(NotificationChannel.ChannelType.WECHAT_WORK, reg, content);
        // 2) 飞书群机器人
        pushWebhook(NotificationChannel.ChannelType.FEISHU, reg, content);
        // 3) 内置邮箱（超管邮箱）
        pushEmail(reg, content);
    }

    private void pushWebhook(NotificationChannel.ChannelType type, Registration reg, String content) {
        List<NotificationChannel> channels = channelRepo.findByChannelTypeAndStatus(
                type, NotificationChannel.Status.ACTIVE);
        if (channels.isEmpty()) {
            log.info("未配置激活的 {} 通道，跳过", type);
            return;
        }
        for (NotificationChannel ch : channels) {
            try {
                sendToWecomBot(ch.getWebhookUrl(), content);
                log.info("✅ 推送成功：channel={}, registrationId={}", ch.getName(), reg.getId());
            } catch (Exception e) {
                log.error("❌ 推送失败：channel={}, registrationId={}, webhook={}, error={}",
                        ch.getName(), reg.getId(), ch.getWebhookUrl(), e.getMessage(), e);
            }
        }
    }

    /**
     * V2.0.27 Bug-21：EMAIL 通道推送
     * 通道的 webhookUrl 字段复用为「收件邮箱」（超管邮箱，如 188407408@qq.com）
     */
    private void pushEmail(Registration reg, String content) {
        List<NotificationChannel> channels = channelRepo.findByChannelTypeAndStatus(
                NotificationChannel.ChannelType.EMAIL, NotificationChannel.Status.ACTIVE);
        if (channels.isEmpty()) {
            log.info("未配置激活的 EMAIL 通道，跳过邮件推送");
            return;
        }
        for (NotificationChannel ch : channels) {
            String to = ch.getWebhookUrl();
            if (to == null || to.isBlank() || !to.contains("@")) {
                log.warn("EMAIL 通道 {} 的收件邮箱无效: {}", ch.getName(), to);
                continue;
            }
            if (mailSender == null || !emailProps.isEnabled()) {
                log.warn("📧 [DEV 模式] 邮件未真实发送（lingyao.notify.email.enabled=false）。"
                        + "收件: {}, 主题: {}, 内容:\n{}", to, emailSubject(reg), content);
                continue;
            }
            try {
                sendHtmlMail(to, emailSubject(reg), buildHtmlContent(reg));
                log.info("✅ 邮件推送成功：to={}, registrationId={}", to, reg.getId());
            } catch (Exception e) {
                log.error("❌ 邮件推送失败：to={}, registrationId={}, error={}",
                        to, reg.getId(), e.getMessage(), e);
            }
        }
    }

    private String emailSubject(Registration reg) {
        return String.format("【凌瑶智数】新报名：%s / %s（意向 %s）",
                nullSafe(reg.getName()), nullSafe(reg.getCompany()),
                nullSafe(reg.getInterestedProducts()));
    }

    private String buildHtmlContent(Registration reg) {
        return String.format("""
                <h2 style="color:#0A1F3D;">新报名通知</h2>
                <table border="1" cellpadding="8" cellspacing="0" style="border-collapse:collapse;font-size:14px;">
                <tr><td>姓名</td><td><strong>%s</strong></td></tr>
                <tr><td>公司</td><td>%s</td></tr>
                <tr><td>职位</td><td>%s</td></tr>
                <tr><td>手机</td><td>%s</td></tr>
                <tr><td>邮箱</td><td>%s</td></tr>
                <tr><td>意向产品</td><td>%s</td></tr>
                <tr><td>留言</td><td>%s</td></tr>
                </table>
                <p style="color:#94A3B8;font-size:12px;">报名 ID: %d · 提交时间: %s</p>
                """,
                nullSafe(reg.getName()), nullSafe(reg.getCompany()),
                nullSafe(reg.getPosition()), nullSafe(reg.getPhone()),
                nullSafe(reg.getEmail()), nullSafe(reg.getInterestedProducts()),
                nullSafe(reg.getMessage()), reg.getId(),
                reg.getCreatedAt() == null ? "-" : reg.getCreatedAt().toString());
    }

    private void sendHtmlMail(String to, String subject, String html) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(emailProps.getFrom() != null && !emailProps.getFrom().isBlank()
                ? emailProps.getFrom() : emailProps.getUsername());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(html, true);
        mailSender.send(message);
    }

    /**
     * V2.0.41：通用 HTML 邮件发送（服务间通知 / 审计告警复用）
     *
     * @param to      收件邮箱
     * @param subject 主题
     * @param html    HTML 正文
     * @return true=已发送；false=mail 未配置（降级，仅记日志）
     */
    public boolean sendGenericHtmlMail(String to, String subject, String html) {
        if (mailSender == null) {
            log.warn("[V2.0.41] JavaMailSender 未配置，邮件降级为日志。to={}, subject={}", to, subject);
            return false;
        }
        try {
            sendHtmlMail(to, subject, html);
            return true;
        } catch (Exception e) {
            log.error("[V2.0.41] 邮件发送失败：to={}, subject={}, error={}", to, subject, e.getMessage(), e);
            return false;
        }
    }

    /**
     * V2.0.41：取激活 EMAIL 通道的收件邮箱（超管邮箱）；无通道返回 null
     */
    public String firstActiveEmailRecipient() {
        return channelRepo.findByChannelTypeAndStatus(
                        NotificationChannel.ChannelType.EMAIL, NotificationChannel.Status.ACTIVE).stream()
                .map(NotificationChannel::getWebhookUrl)
                .filter(u -> u != null && u.contains("@"))
                .findFirst().orElse(null);
    }

    private String buildRegistrationContent(Registration reg) {
        return String.format(
            "【新报名通知】\n姓名: %s\n公司: %s\n职位: %s\n手机: %s\n邮箱: %s\n意向产品: %s\n留言: %s",
            nullSafe(reg.getName()),
            nullSafe(reg.getCompany()),
            nullSafe(reg.getPosition()),
            nullSafe(reg.getPhone()),
            nullSafe(reg.getEmail()),
            nullSafe(reg.getInterestedProducts()),
            nullSafe(reg.getMessage())
        );
    }

    private void sendToWecomBot(String webhookUrl, String content) throws Exception {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            throw new IllegalArgumentException("webhook URL 为空");
        }
        // 企微群机器人消息体（也兼容飞书格式）
        String json = String.format(
            "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
            content.replace("\"", "\\\"").replace("\n", "\\n")
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
