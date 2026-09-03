package com.lingyao.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 算术图形验证码服务 — V2.0.66
 *
 * 设计（零外部依赖、纯自研）：
 * - 出题：a±b（结果非负）或 a×b；返回 captchaId + SVG 图形
 * - 存储：ConcurrentHashMap<captchaId, Entry>，2 分钟过期，懒清理
 * - 校验：一次性消费（无论对错都作废）
 * - 安全：captchaId 用 SecureRandom 32 字节；SVG 文字加随机旋转/偏移/颜色
 *   增加机器识别难度；未知 captchaId 统一返回"验证码错误"防枚举
 */
@Service
@Slf4j
public class CaptchaService {

    /** 验证码条目 */
    private record Entry(String answer, LocalDateTime expiresAt) {}

    /** V2.0.66：2FA 挑战凭证（绑定用户名，一次性，10 分钟）——密码+图形验证码已验过 */
    private record Ticket(String username, LocalDateTime expiresAt) {}

    private static final long TTL_SECONDS = 120;
    private static final long TICKET_TTL_SECONDS = 600;
    private static final int MAX_ENTRIES = 10000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Map<String, Ticket> ticketStore = new ConcurrentHashMap<>();
    private final AtomicLong totalIssued = new AtomicLong();
    private final AtomicLong totalVerified = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    /** 生成验证码：返回 captchaId + SVG + 过期秒数 */
    public Map<String, Object> generate() {
        cleanupIfNeeded();
        int a = random.nextInt(10) + 1;
        int b = random.nextInt(10) + 1;
        int op = random.nextInt(3);
        int answer;
        String text;
        switch (op) {
            case 0 -> { answer = a + b; text = a + " + " + b + " = ?"; }
            case 1 -> {
                if (a < b) { int t = a; a = b; b = t; }
                answer = a - b;
                text = a + " - " + b + " = ?";
            }
            default -> {
                if (a > 5) a = random.nextInt(5) + 2;
                if (b > 5) b = random.nextInt(5) + 2;
                answer = a * b;
                text = a + " x " + b + " = ?";
            }
        }

        String captchaId = newSecureId();
        store.put(captchaId, new Entry(String.valueOf(answer), LocalDateTime.now().plusSeconds(TTL_SECONDS)));
        totalIssued.incrementAndGet();
        log.debug("[Captcha] 出题 id={} text={}", captchaId, text);
        return Map.of(
                "captchaId", captchaId,
                "svg", renderSvg(text),
                "expiresIn", TTL_SECONDS
        );
    }

    /** 校验（一次性消费）：对 → true 并作废；错 → false 并作废 */
    public boolean verify(String captchaId, String answer) {
        if (captchaId == null || captchaId.isBlank() || answer == null || answer.isBlank()) return false;
        cleanupIfNeeded();
        Entry e = store.remove(captchaId);
        if (e == null) { totalFailed.incrementAndGet(); return false; }
        if (LocalDateTime.now().isAfter(e.expiresAt())) { totalFailed.incrementAndGet(); return false; }
        boolean ok = e.answer().equals(answer.trim());
        if (ok) totalVerified.incrementAndGet(); else totalFailed.incrementAndGet();
        return ok;
    }

    /** V2.0.66：签发 2FA 挑战凭证（绑定用户名，10 分钟一次性） */
    public String issueTicket(String username) {
        String ticket = newSecureId();
        ticketStore.put(ticket, new Ticket(username, LocalDateTime.now().plusSeconds(TICKET_TTL_SECONDS)));
        return ticket;
    }

    /** V2.0.66：校验挑战凭证（一次性消费 + 绑定用户名防挪用） */
    public boolean verifyTicket(String ticket, String username) {
        if (ticket == null || ticket.isBlank() || username == null) return false;
        Ticket t = ticketStore.remove(ticket);
        if (t == null) return false;
        return t.username().equals(username) && LocalDateTime.now().isBefore(t.expiresAt());
    }

    /** 过期清理：超过半容量时全表扫一遍（懒清理，避免定时任务） */
    private void cleanupIfNeeded() {
        if (store.size() < MAX_ENTRIES / 2) return;
        LocalDateTime now = LocalDateTime.now();
        store.entrySet().removeIf(en -> now.isAfter(en.getValue().expiresAt()));
    }

    private String newSecureId() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** SVG 渲染：随机颜色/旋转/干扰线 */
    private String renderSvg(String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='130' height='44' viewBox='0 0 130 44'>");
        sb.append("<rect width='130' height='44' fill='#f5f7fa'/>");
        for (int i = 0; i < 5; i++) {
            sb.append("<line x1='").append(random.nextInt(130))
              .append("' y1='").append(random.nextInt(44))
              .append("' x2='").append(random.nextInt(130))
              .append("' y2='").append(random.nextInt(44))
              .append("' stroke='#c0c8d4' stroke-width='1'/>");
        }
        int x = 12;
        for (char ch : text.toCharArray()) {
            String color = COLORS[random.nextInt(COLORS.length)];
            int rot = random.nextInt(51) - 25;
            int dy = random.nextInt(7);
            sb.append("<text x='").append(x)
              .append("' y='").append(25 + dy)
              .append("' font-size='20' font-weight='bold' fill='").append(color)
              .append("' transform='rotate(").append(rot).append(" ").append(x).append(" ").append(25 + dy).append(")")
              .append("'>").append(escapeXml(ch)).append("</text>");
            x += ch == ' ' ? 6 : 11;
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static String escapeXml(char ch) {
        if (ch == '&') return "&amp;";
        if (ch == '<') return "&lt;";
        if (ch == '>') return "&gt;";
        if (ch == '\'') return "&apos;";
        if (ch == '"') return "&quot;";
        return String.valueOf(ch);
    }

    private static final String[] COLORS = {"#3b5998", "#2c7a3f", "#a0522d", "#5566aa", "#7a3b8f", "#8b4513"};
}
