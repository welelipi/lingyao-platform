package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.LoginRequest;
import com.lingyao.platform.dto.LoginResponse;
import com.lingyao.platform.security.ClientIpResolver;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.security.JwtUtil;
import com.lingyao.platform.service.AuthService;
import com.lingyao.platform.service.CaptchaService;
import com.lingyao.platform.service.EmailOtpService;
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

    // V2.0.66：算术图形验证码
    @Autowired
    private CaptchaService captchaService;

    // V2.0.69 A+D+B：邮箱验证码服务
    @Autowired
    private EmailOtpService emailOtpService;

    // V2.0.20 P1 阶段 A：解析 token 拿 jti
    @Autowired
    private JwtUtil jwtUtil;

    // V2.0.78.25：当前生效的 JWT 密钥（供 /jwt-fingerprint 计算不可逆指纹）
    @org.springframework.beans.factory.annotation.Value("${lingyao.jwt.secret}")
    private String secretValue;

    // V2.0.71 PRD-1.3：统一 IP 口径
    @Autowired
    private ClientIpResolver clientIpResolver;

    /**
     * V2.0.71 PRD-1.3：统一客户端 IP 取值（Filter 链与业务层同源同值）
     */
    private String clientIp(HttpServletRequest httpReq) {
        return clientIpResolver.resolve(httpReq);
    }

    /**
     * V2.0.78.25：密钥指纹公开端点（免鉴权）
     *
     * 背景（r7 实锤缺陷）：JWT 密钥轮换后，浏览器 localStorage 里存量 token
     * 全部无法验签 → 所有接口 401 → 用户卡在「产品列表空白 + KPI 不展示」。
     * 而修复后的前端 logout() 虽能自愈，但**无法主动触发** —— 用户必须先点一次
     * 页面、等到某次请求 401，才会被踢回登录页。体验上等同于「登录后坏了」。
     *
     * 机制：后端暴露当前密钥的**不可逆指纹**（不是密钥本身）。
     * 前端在 localStorage 缓存签发时拿到的指纹；每次加载页面先比对：
     *   一致 → token 可信，正常用
     *   不一致 → 说明密钥已轮换，存量 token 必然失效 → 立即清本地状态并跳登录页
     *
     * 安全考量：
     *   - 只回指纹（SHA-256 前 16 hex），不回密钥，无法用于撞库
     *   - 指纹不含明文密钥，泄露无实质危害（等同于公开"密钥已换代"这一事实）
     *   - 端点本身免鉴权是必要的 —— token 已失效时无法通过鉴权拿到该信息
     */
    @GetMapping("/jwt-fingerprint")
    public ApiResponse<Map<String, Object>> jwtFingerprint() {
        java.security.MessageDigest md = null;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(secretValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < d.length; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return ApiResponse.ok(Map.of("fingerprint", sb.toString(), "epochMillis", System.currentTimeMillis()));
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("fingerprint", "unavailable"));
        }
    }

    /** V2.0.66：获取图形验证码（公开端点，登录页加载/点击刷新时调用） */
    @GetMapping("/captcha")
    public ApiResponse<Map<String, Object>> captcha() {
        return ApiResponse.ok(captchaService.generate());
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpReq) {
        try {
            // V2.0.69 D：透传 User-Agent（设备指纹用）
            LoginResponse data = authService.login(req, clientIp(httpReq), httpReq.getHeader("User-Agent"));
            return ApiResponse.ok(data.isMustChangePassword() ? "登录成功，请修改初始密码" : "登录成功", data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ═══════════════════════ V2.0.69 A+D+B：邮箱验证码三端点 ═══════════════════════

    /**
     * D：发送新设备登录验证码（登录页弹窗调用，公开端点——凭用户名+邮箱在库校验，不暴露是否存在）
     */
    @PostMapping("/device-otp/send")
    public ApiResponse<Map<String, Object>> sendDeviceOtp(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username == null || username.isBlank()) return ApiResponse.fail("用户名不能为空");
        var user = authService.findByUsernameForOtp(username);
        if (user == null) return ApiResponse.ok("如果该账号已绑定邮箱，验证码将发送至邮箱", Map.of("ok", true));
        var result = emailOtpService.sendOtp(EmailOtpService.PURPOSE_DEVICE_LOGIN, username, user.getEmail());
        if (Boolean.TRUE.equals(result.get("ok"))) return ApiResponse.ok((String) result.get("message"), result);
        return ApiResponse.fail((String) result.get("message"));
    }

    /**
     * B：发送改密码验证码（需已登录——改密本身是登录后操作）
     */
    @PostMapping("/change-password-otp/send")
    public ApiResponse<Map<String, Object>> sendChangePasswordOtp() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null || cu.getUserId() == null) return ApiResponse.fail(401, "请先登录");
        var user = authService.findByUsernameForOtp(cu.getUsername());
        if (user == null) return ApiResponse.fail("用户不存在");
        var result = emailOtpService.sendOtp(EmailOtpService.PURPOSE_CHANGE_PASSWORD, cu.getUsername(), user.getEmail());
        if (Boolean.TRUE.equals(result.get("ok"))) return ApiResponse.ok((String) result.get("message"), result);
        return ApiResponse.fail((String) result.get("message"));
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
            // V2.0.69 B：携带邮箱验证码（已绑邮箱用户必填，后端强校验）
            authService.changePassword(cu.getUserId(), oldPassword, newPassword, body.get("emailOtpCode"));
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
            authService.logout(cu.getUserId(), claims, clientIp(httpReq));
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
            LoginResponse resp = authService.refresh(refreshToken, clientIp(httpReq));
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
            Map<String, Object> data = authService.forgotPassword(emailOrUsername, clientIp(httpReq));
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
            authService.resetPassword(token, newPassword, clientIp(httpReq));
            return ApiResponse.ok("密码重置成功，请使用新密码登录", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
