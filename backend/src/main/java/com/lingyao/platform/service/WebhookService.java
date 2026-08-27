package com.lingyao.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingyao.platform.entity.ReleaseHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook 推送服务 — V2.0.5 R-7
 *
 * 当前支持：飞书群机器人（card 消息）
 * 后续可扩展：钉钉、企业微信
 *
 * 配置（application.yml）：
 *   lingyao.release.webhook-url=https://open.feishu.cn/open-apis/bot/v2/hook/xxx
 *   lingyao.release.webhook-enabled=true
 *
 * 消息格式（飞书 card）：
 *   - 标题：✅/❌ staging/prod 部署成功/失败
 *   - 字段：环境、版本、部署人、耗时、起止时间
 *   - 备注：历史链接
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${lingyao.release.webhook-url:}")
    private String webhookUrl;

    @Value("${lingyao.release.webhook-enabled:false}")
    private boolean webhookEnabled;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 推送发布结果到飞书/Webhook
     *
     * @throws RuntimeException 推送失败（让 caller 记录 webhook_status）
     */
    public void pushReleaseNotification(ReleaseHistory hist) {
        if (!webhookEnabled || webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[R-7] Webhook 未启用，跳过推送（env={}, version={}, status={}）",
                    hist.getEnv(), hist.getVersion(), hist.getStatus());
            return;
        }

        try {
            Map<String, Object> payload = buildFeishuCard(hist);
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
            }
            log.info("[R-7] Webhook 推送成功: env={}, version={}, status={}",
                    hist.getEnv(), hist.getVersion(), hist.getStatus());
        } catch (Exception e) {
            throw new RuntimeException("Webhook 推送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造飞书消息卡片（interactive card）
     */
    private Map<String, Object> buildFeishuCard(ReleaseHistory hist) {
        boolean isSuccess = hist.getStatus() == ReleaseHistory.ReleaseStatus.SUCCESS;
        boolean isFailed = hist.getStatus() == ReleaseHistory.ReleaseStatus.FAILED;
        String envLabel = hist.getEnv() == ReleaseHistory.ReleaseEnv.STAGING ? "🧪 Staging (9092)" : "🚀 Prod (9091)";
        String emoji = isSuccess ? "✅" : (isFailed ? "❌" : "⏳");
        String titleText = isSuccess
                ? emoji + " " + envLabel + " 部署成功"
                : (isFailed ? emoji + " " + envLabel + " 部署失败" : emoji + " " + envLabel + " 部署中");

        // 颜色：绿=成功，红=失败，蓝=运行中
        String color = isSuccess ? "green" : (isFailed ? "red" : "blue");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", Map.of("tag", "plain_text", "content", titleText));
        header.put("template", color);

        // 字段
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("版本", hist.getVersion());
        fields.put("部署人", hist.getDeployedBy());
        fields.put("状态", hist.getStatus().name());
        if (hist.getStartedAt() != null) {
            fields.put("开始", hist.getStartedAt().format(TS_FMT));
        }
        if (hist.getFinishedAt() != null) {
            fields.put("结束", hist.getFinishedAt().format(TS_FMT));
        }
        if (hist.getDurationSec() != null) {
            fields.put("耗时", hist.getDurationSec() + " 秒");
        }
        if (hist.getErrorMessage() != null && !hist.getErrorMessage().isBlank()) {
            fields.put("错误", truncate(hist.getErrorMessage(), 200));
        }

        // 构造 elements（fields 数组）
        Map<String, Object> fieldsObj = new LinkedHashMap<>();
        fieldsObj.put("tag", "div");
        fieldsObj.put("fields", fields.entrySet().stream().map(e -> Map.of(
                "is_short", true,
                "text", Map.of("tag", "lark_md", "content", "**" + e.getKey() + "**\n" + e.getValue())
        )).toList());

        Map<String, Object> note = new LinkedHashMap<>();
        note.put("tag", "note");
        note.put("elements", java.util.List.of(Map.of(
                "tag", "plain_text",
                "content", "凌瑶智数 · 平台超管发布通知 · " + LocalDateTime.now().format(TS_FMT)
        )));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("header", header);
        card.put("elements", java.util.List.of(fieldsObj, note));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "interactive");
        payload.put("card", card);
        return payload;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}