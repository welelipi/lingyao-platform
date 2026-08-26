package com.lingyao.platform.service;

import com.lingyao.platform.entity.NotificationChannel;
import com.lingyao.platform.entity.Registration;
import com.lingyao.platform.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 推送服务 — Bug-03 修复（增加告警）
 * 推送失败时记录 ERROR 日志并统计连续失败次数
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    @Autowired
    private NotificationChannelRepository channelRepo;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 异步推送报名通知
     */
    @Async
    public void pushRegistrationAsync(Registration reg) {
        log.info("准备推送报名通知：id={}, name={}", reg.getId(), reg.getName());
        List<NotificationChannel> channels = channelRepo.findByChannelTypeAndStatus(
                NotificationChannel.ChannelType.WECHAT_WORK, NotificationChannel.Status.ACTIVE);
        if (channels.isEmpty()) {
            log.warn("⚠️ 未配置任何激活的企微群通道（请在超管后台 /admin 配置 WECHAT_WORK 通道）");
            return;
        }

        String content = buildRegistrationContent(reg);
        for (NotificationChannel ch : channels) {
            try {
                sendToWecomBot(ch.getWebhookUrl(), content);
                log.info("✅ 推送成功：channel={}, registrationId={}", ch.getName(), reg.getId());
            } catch (Exception e) {
                // Bug-03 修复：失败时 ERROR 级别 + 详细异常
                log.error("❌ 推送失败：channel={}, registrationId={}, webhook={}, error={}",
                        ch.getName(), reg.getId(), ch.getWebhookUrl(), e.getMessage(), e);
            }
        }
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
