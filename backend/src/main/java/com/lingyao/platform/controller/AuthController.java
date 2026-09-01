package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.LoginRequest;
import com.lingyao.platform.dto.LoginResponse;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.security.JwtUtil;
import com.lingyao.platform.service.AuthService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    // V2.0.20 P1 阶段 A：解析 token 拿 jti
    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpReq) {
        try {
            LoginResponse data = authService.login(req, httpReq.getRemoteAddr());
            return ApiResponse.ok(data.isMustChangePassword() ? "登录成功，请修改初始密码" : "登录成功", data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * Bug-12：注册端点暂未启用（多租户通过邀请链接开通）
     * 返回 410 Gone 明确告知
     */
    @PostMapping("/register")
    public ApiResponse<?> register() {
        return ApiResponse.fail(410, "自助注册暂未开放，请联系您的管理员获取邀请链接");
    }

    /**
     * P0-A：修改密码（私有化首登强制改密 + 日常密码修改通用）
     *
     * 鉴权：需已登录（Cookie 或 Authorization: Bearer xxx）
     * 即使 mustChangePassword=true，也能正常改密（Filter 不阻止）
     */
    @PostMapping("/change-password")
    public ApiResponse<?> changePassword(@RequestBody Map<String, String> body) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (oldPassword == null || oldPassword.isEmpty()) {
            return ApiResponse.fail("原密码不能为空");
        }
        if (newPassword == null || newPassword.isEmpty()) {
            return ApiResponse.fail("新密码不能为空");
        }
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null || cu.getUserId() == null) {
            return ApiResponse.fail(401, "请先登录");
        }
        try {
            authService.changePassword(cu.getUserId(), oldPassword, newPassword);
            return ApiResponse.ok("密码修改成功，下次登录请使用新密码", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ═══════════════════════ V2.0.20 P1 阶段 A：/me + /logout ═══════════════════════

    /**
     * P1.1 GET /api/auth/me
     * 返回当前用户的 profile + company + products（不重发 token）
     * 鉴权：需已登录
     */
    @GetMapping("/me")
    public ApiResponse<LoginResponse> me() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null || cu.getUserId() == null) {
            return ApiResponse.fail(401, "请先登录");
        }
        try {
            LoginResponse data = authService.getMe(cu.getUserId());
            return ApiResponse.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * P1.2 POST /api/auth/logout
     * 把当前 token 的 jti 写入黑名单，JwtAuthFilter 下次校验时拒绝
     *
     * 注意：必须是 POST 而非 GET（防止 CSRF + 浏览器预取）
     * 即使未登录也能调（幂等），但返回 401（前端可忽略）
     */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest httpReq) {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null || cu.getUserId() == null) {
            // 未登录调用 → 返回 200 幂等
            return ApiResponse.ok(Map.of("loggedOut", true, "alreadyLoggedOut", true));
        }
        // 从 SecurityContext 拿到 token 的 jti（需要从 request attribute 读 Claims）
        String authHeader = httpReq.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ApiResponse.fail(401, "缺少 Authorization 头");
        }
        String token = authHeader.substring(7);
        try {
            Claims claims = jwtUtil.parse(token);
            authService.logout(cu.getUserId(), claims, httpReq.getRemoteAddr());
            return ApiResponse.ok("已退出登录", Map.of("loggedOut", true, "jti", String.valueOf(claims.getId())));
        } catch (Exception e) {
            // token 已过期或无效 → 仍然返回 200（前端清 token 就够了）
            return ApiResponse.ok("会话已失效，无需注销", Map.of("loggedOut", true, "alreadyExpired", true));
        }
    }
}
