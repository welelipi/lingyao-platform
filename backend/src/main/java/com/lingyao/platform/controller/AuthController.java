package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.LoginRequest;
import com.lingyao.platform.dto.LoginResponse;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.service.AuthService;
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
}
