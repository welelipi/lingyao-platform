package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.service.SensitiveOtpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 敏感操作 OTP 端点 — V2.0.77.15
 *
 * 提供两个端点：
 * 1. POST /api/auth/sensitive-otp/send  - 发送敏感操作验证码（已登录）
 * 2. POST /api/auth/sensitive-otp/ticket - 验证 OTP 并签发一次性 ticket（前端两段式用）
 *
 * 接入的 6 个敏感操作端点（详见 SensitiveOtpService 注释）：
 * - PUT  /api/admin/companies/{id}
 * - POST /api/admin/companies/{id}/suspend
 * - POST /api/admin/companies/{id}/activate
 * - POST /api/admin/users/{userId}/reset-password
 * - PUT  /api/company-admin/users/{userId}/role
 * - PUT  /api/company-admin/sso-config/{provider}
 *
 * 简化模式：前端在敏感操作请求 body 里直接带 emailOtpCode，controller 首行调用
 * sensitiveOtpService.verify(operation, emailOtpCode, currentUsername) 即可。
 * 无需前端两段式（先 send 再 submit），一次请求完成。
 */
@RestController
@RequestMapping("/api/auth/sensitive-otp")
public class SensitiveOtpController {

    @Autowired
    private SensitiveOtpService sensitiveOtpService;

    /**
     * 发送敏感操作验证码
     * 请求体：{"operation": "COMPANY_CHANGE_GEOM_OR_CONTRACT" ...}
     * operation 是审计用标识（不参与校验逻辑），便于 audit_log 区分操作类型
     */
    @PostMapping("/send")
    public ApiResponse<Map<String, Object>> sendSensitiveOtp(@RequestBody Map<String, String> body) {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null || cu.getUsername() == null) return ApiResponse.fail(401, "请先登录");
        String operation = body.getOrDefault("operation", "UNKNOWN");
        var result = sensitiveOtpService.sendSensitiveOtp(cu.getUsername(), operation);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ApiResponse.ok((String) result.get("message"), result);
        }
        return ApiResponse.fail((String) result.get("message"));
    }

    /**
     * 验证 OTP 并签发一次性 ticket（前端两段式预留）
     * 请求体：{"otpCode": "123456", "operation": "..."}
     */
    @PostMapping("/ticket")
    public ApiResponse<Map<String, Object>> verifyAndTicket(@RequestBody Map<String, String> body) {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null || cu.getUsername() == null) return ApiResponse.fail(401, "请先登录");
        String operation = body.getOrDefault("operation", "UNKNOWN");
        String otpCode = body.get("otpCode");
        try {
            String ticket = sensitiveOtpService.verifyAndIssueTicket(cu.getUsername(), operation, otpCode);
            return ApiResponse.ok("验证通过", Map.of("ticket", ticket, "expiresIn", 300));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}