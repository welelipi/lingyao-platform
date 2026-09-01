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

    // ═══════════════════════ V2.0.21 P1.4 阶段 B：refresh 端点 ═══════════════════════

    /**
     * P1.4 POST /api/auth/refresh
     * 用 refresh_token 换新 access_token + 新 refresh_token（滑动续期）
     *
     * 请求体：{ "refreshToken": "..." }
     * 返回：LoginResponse（含新 token + 新 refreshToken）
     *
     * 必须未鉴权（refresh_token 本身就是凭证）
     * 失败场景：
     * - refresh_token 为空 → 400
     * - refresh_token 不存在 → 401
     * - refresh_token 已过期/已撤销/已使用 → 401（重放检测会撤销该用户所有会话）
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestBody Map<String, String> body, HttpServletRequest httpReq) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ApiResponse.fail(400, "refreshToken 不能为空");
        }
        try {
            LoginResponse resp = authService.refresh(refreshToken, httpReq.getRemoteAddr());
            return ApiResponse.ok("续期成功", resp);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(401, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // V2.0.21 P1.5 阶段 B：forgot-password + reset-password
    // ══════════════════════════════════════════════════════════════════

    /**
     * P1.5 POST /api/auth/forgot-password
     * 提交 email/username，返回 ok（无论用户是否存在都返回 200，防枚举）
     *
     * 请求体：{ "email": "admin@lingyao.cn" } 或 { "username": "admin" }
     * 返回：{ success, message, devModeToken?, resetUrl?, expiresIn? }
     *        devModeToken 仅 dev 模式返回（邮件通道未配置时前端能直接用）
     */
    @PostMapping("/forgot-password")
    public ApiResponse<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> body,
                                                            HttpServletRequest httpReq) {
        String emailOrUsername = body.get("email");
        if (emailOrUsername == null || emailOrUsername.isBlank()) {
            emailOrUsername = body.get("username");
        }
        if (emailOrUsername == null || emailOrUsername.isBlank()) {
            return ApiResponse.fail(400, "请提供 email 或 username");
        }
        try {
            Map<String, Object> data = authService.forgotPassword(emailOrUsername, httpReq.getRemoteAddr());
            return ApiResponse.ok(data.getOrDefault("message", "已处理").toString(), data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * P1.5 POST /api/auth/reset-password
     * 用 reset_token 重置密码（无需登录，token 本身就是凭证）
     *
     * 请求体：{ "token": "...", "newPassword": "newpwd123" }
     */
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@RequestBody Map<String, String> body, HttpServletRequest httpReq) {
        String token = body.get("token");
        String newPassword = body.get("newPassword");
        if (token == null || token.isBlank()) {
            return ApiResponse.fail(400, "token 不能为空");
        }
        if (newPassword == null || newPassword.isBlank()) {
            return ApiResponse.fail(400, "newPassword 不能为空");
        }
        try {
            authService.resetPassword(token, newPassword, httpReq.getRemoteAddr());
            return ApiResponse.ok("密码重置成功，请使用新密码登录", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
