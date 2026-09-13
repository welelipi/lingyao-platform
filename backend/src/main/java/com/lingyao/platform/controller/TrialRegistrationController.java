package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.TrialRegistrationRequest;
import com.lingyao.platform.entity.TrialRegistration;
import com.lingyao.platform.service.TrialRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trial 注册 Controller · V2.0.78.0（主站 D-5 Day 1）
 *
 * 端点：
 * - POST /api/trial/register          公开 · ① 纯公开（A 拍板）
 * - GET  /api/trial/check             公开 · 检查手机号是否已注册
 * - GET  /api/trial/admin/list        超管 · 列出全部 trial
 */
@RestController
@RequestMapping("/api/trial")
public class TrialRegistrationController {

    @Autowired
    private TrialRegistrationService service;

    /**
     * 公开注册端点（V2.0.78.0 · ① 纯公开 · 频次限流 24h × 3）
     *
     * 成功响应：
     * {
     *   "code": 0,
     *   "message": "试用开通成功",
     *   "data": {
     *     "registrationId": 123,
     *     "companyId": 100,
     *     "trialExpiresAt": "2026-09-20T14:30:00",
     *     "trialDays": 7,
     *     "username": "13800001111",
     *     "defaultPassword": "123456",  // 仅首次返回，提示用户改密
     *     "products": ["GEO", "HPD", "AIDD", "DINFO", "PORM"]
     *   }
     * }
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody TrialRegistrationRequest req,
                                                      HttpServletRequest request) {
        String ip = clientIp(request);
        try {
            TrialRegistration reg = service.register(req, ip);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("registrationId", reg.getId());
            data.put("companyId", reg.getCompanyId());
            data.put("trialExpiresAt", reg.getTrialExpiresAt());
            data.put("trialDays", 7);
            data.put("username", req.getPhone());
            data.put("defaultPassword", req.getPhone().substring(req.getPhone().length() - 6));
            data.put("products", java.util.List.of("GEO", "HPD", "AIDD", "DINFO", "PORM"));
            data.put("loginUrl", "http://localhost:9091/portal.html");  // V2.0.77.25：8765 退役 → 9091
            return ApiResponse.ok("试用开通成功，开通链接已发送到 " + req.getEmail(), data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail(500, "试用开通失败：" + e.getMessage());
        }
    }

    /** 提取客户端真实 IP（处理 Nginx X-Forwarded-For 头） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
