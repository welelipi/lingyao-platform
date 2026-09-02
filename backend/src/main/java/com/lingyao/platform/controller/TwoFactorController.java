package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.service.TwoFactorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 双因素认证 Controller — V2.0.42 P2.4 · TOTP 真实现
 *
 * 端点（V2.0.26 skeleton → V2.0.42 全部激活）：
 * - GET    /api/auth/2fa/status         当前用户 2FA 状态
 * - POST   /api/auth/2fa/setup          开启设置（返回 secret + otpauth URI，明文仅此一次）
 * - POST   /api/auth/2fa/verify-setup   验证首次设置（返回 8 个 backup codes，明文仅此一次）
 * - POST   /api/auth/2fa/verify-login   登录第二步（TOTP 或 backup code）
 * - POST   /api/auth/2fa/disable        关闭 2FA（需密码）
 * - POST   /api/auth/2fa/backup-codes   重生成 backup codes（需密码）
 *
 * 注：登录流程 challenge（mfa_required）暂未在 AuthService 强制启用——本期先开放
 *     设置/验证端点，登录强校验随前端 2FA UI 一起上线（V2.0.43+）。
 */
@RestController
@RequestMapping("/api/auth/2fa")
public class TwoFactorController {

    @Autowired
    private TwoFactorService twoFactorService;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserId() == null) {
            return ApiResponse.fail(401, "未登录");
        }
        return ApiResponse.ok(twoFactorService.getStatus(cu.getUserId()));
    }

    @GetMapping("/users/{userId}/status")
    public ApiResponse<Map<String, Object>> userStatus(@PathVariable Long userId) {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || !cu.isPlatformAdmin()) {
            return ApiResponse.fail(403, "仅平台超管可查询他人 2FA 状态");
        }
        return ApiResponse.ok(twoFactorService.getStatus(userId));
    }

    /** 开启 2FA 设置：返回 secret + otpauth URI（secret 明文仅此一次返回） */
    @PostMapping("/setup")
    public ApiResponse<?> setup() {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserId() == null) return ApiResponse.fail(401, "未登录");
        try {
            return ApiResponse.ok("请用 Authenticator 扫码后输入 6 位验证码", twoFactorService.generateSetup(cu.getUserId()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /** 验证首次设置：通过后启用并返回 backup codes（明文仅此一次） */
    @PostMapping("/verify-setup")
    public ApiResponse<?> verifySetup(@RequestBody Map<String, String> body) {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserId() == null) return ApiResponse.fail(401, "未登录");
        try {
            return ApiResponse.ok("2FA 启用成功", twoFactorService.verifySetup(cu.getUserId(), body.get("code")));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /** 登录第二步验证（TOTP 6 位码或备份码） */
    @PostMapping("/verify-login")
    public ApiResponse<?> verifyLogin(@RequestBody Map<String, String> body) {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserId() == null) return ApiResponse.fail(401, "未登录");
        boolean ok = twoFactorService.verifyLoginCode(cu.getUserId(), body.get("code"));
        return ok ? ApiResponse.ok("验证通过", Map.of("verified", true))
                  : ApiResponse.fail(401, "验证码错误");
    }

    /** 关闭 2FA（密码二次确认） */
    @PostMapping("/disable")
    public ApiResponse<?> disable(@RequestBody Map<String, String> body) {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserId() == null) return ApiResponse.fail(401, "未登录");
        try {
            twoFactorService.disable(cu.getUserId(), body.get("password"));
            return ApiResponse.ok("2FA 已关闭", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /** 重生成 backup codes（密码二次确认；明文仅此一次） */
    @PostMapping("/backup-codes")
    public ApiResponse<?> backupCodes(@RequestBody Map<String, String> body) {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserId() == null) return ApiResponse.fail(401, "未登录");
        try {
            String[] codes = twoFactorService.regenerateBackupCodes(cu.getUserId(), body.get("password"));
            return ApiResponse.ok("备份码已重生成", Map.of("backupCodes", codes));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }
}
