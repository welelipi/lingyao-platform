package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.security.JwtUtil;
import com.lingyao.platform.service.AuditLogService;
import com.lingyao.platform.service.PushService;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2.0.41 — 服务间通知接口（子平台复用主站邮件基础设施）
 *
 * 背景（主人 16:53 拍板"除 P0 其他都做"）：
 * - V2.0.38 审批全面下放子平台后，员工申请/审批结果的「通知超管」职责也在子平台
 * - 但邮件基础设施（SMTP 授权码、EMAIL 通道、模板）在主站已成熟
 * - 本接口让子平台服务端经主站 SMTP 发信，避免每家子平台重复配置邮箱
 *
 * 鉴权：与 /api/internal/grants/* 一致 —— 子平台用共享 LINGYAO_JWT_SECRET 签
 *       service JWT（audience=lingyao-service，5 分钟），用户 token 冒充被拦
 *
 * Body: {
 *   caller:    "GEOM",            // 调用方平台标识（进审计）
 *   subject:   "【GEOM 棱镜】新权限申请",
 *   body:      "用户 xxx 申请了 ...",  // 支持简单 HTML（<br> 换行）
 *   to:        "xxx@qq.com",       // 选填；缺省发到主站激活 EMAIL 通道的超管邮箱
 * }
 */
@RestController
@RequestMapping("/api/internal/notify")
public class InternalNotifyController {

    @Autowired private JwtUtil jwtUtil;
    @Autowired private PushService pushService;
    @Autowired private AuditLogService auditLogService;

    private String requireService(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            return jwtUtil.parseServiceToken(authHeader.substring(7).trim());
        } catch (JwtException e) {
            return null;
        }
    }

    @PostMapping("/email")
    public ApiResponse<?> email(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                 @RequestBody Map<String, Object> body) {
        String caller = requireService(authHeader);
        if (caller == null) {
            return ApiResponse.fail(401, "缺少有效的服务间鉴权 token");
        }

        String subject = str(body.get("subject"));
        String content = str(body.get("body"));
        if (subject == null || content == null) {
            return ApiResponse.fail(400, "缺少必填字段：subject / body");
        }
        String to = str(body.get("to"));
        if (to == null) {
            to = pushService.firstActiveEmailRecipient();
            if (to == null) {
                return ApiResponse.fail(400, "未指定收件人，且主站无激活的 EMAIL 通道");
            }
        }
        // 收件人白名单防护：防止被当作开放 relay 滥发（只允许主站 EMAIL 通道邮箱 + 主站用户邮箱格式校验）
        String defaultRecipient = pushService.firstActiveEmailRecipient();
        if (!to.equals(defaultRecipient)) {
            return ApiResponse.fail(403, "收件人必须是主站激活 EMAIL 通道的邮箱（当前仅支持超管邮箱）");
        }

        String html = "<div style=\"font-family:sans-serif;max-width:640px;margin:0 auto;\">"
                + "<h2 style=\"color:#16325C;\">".concat(esc(subject)).concat("</h2>")
                + "<div style=\"color:#374151;line-height:1.7;\">".concat(content.replace("\n", "<br>")).concat("</div>")
                + "<hr style=\"border:none;border-top:1px solid #e5e7eb;margin:16px 0;\">"
                + "<p style=\"color:#9ca3af;font-size:12px;\">本邮件由 ".concat(esc(caller))
                + " 平台经凌瑶主站通知服务发送</p></div>";

        boolean sent = pushService.sendGenericHtmlMail(to, subject, html);

        auditLogService.record("INTERNAL_NOTIFY_EMAIL", "NOTIFY",
                caller,
                "子平台[" + caller + "]经主站 SMTP 发信 → " + to + " | subject=" + subject + " | " + (sent ? "SENT" : "DEGRADED"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sent", sent);
        data.put("to", to);
        data.put("caller", caller);
        return sent ? ApiResponse.ok("邮件已发送", data)
                    : ApiResponse.fail(503, "邮件服务未配置或发送失败（已降级记录日志）");
    }

    private String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
