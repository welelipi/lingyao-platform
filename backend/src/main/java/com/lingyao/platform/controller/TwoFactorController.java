package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.service.TwoFactorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 双因素认证 Controller — V2.0.26 P2.4 · 2FA 预留 SKELETON
 *
 * ⚠️ 状态：SKELETON / RESERVED · 所有 PUT/POST/DELETE 操作返回 501 Not Implemented
 *
 * 前端 V2.0.26 集成策略：
 * - GET 端点可以调用（返回实现状态）
 * - 修改类端点（PUT/POST/DELETE）一律收到 501，前端 UI 应该灰显
 *
 * V2.0.27+ 计划开放的端点：
 * - GET    /api/auth/2fa/status         当前用户 2FA 状态
 * - GET    /api/auth/2fa/setup          开启设置（返回 QR 码 + secret）
 * - POST   /api/auth/2fa/verify-setup   验证首次设置
 * - POST   /api/auth/2fa/verify-login   登录第二步
 * - POST   /api/auth/2fa/disable        关闭 2FA（需密码）
 * - POST   /api/auth/2fa/backup-codes   重生成 backup codes
 */
@RestController
@RequestMapping("/api/auth/2fa")
public class TwoFactorController {

    @Autowired
    private TwoFactorService twoFactorService;

    /**
     * 当前用户 2FA 状态（V2.0.26 可用）
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserId() == null) {
            return ApiResponse.fail(401, "未登录");
        }
        return ApiResponse.ok(twoFactorService.getStatus(cu.getUserId()));
    }

    /**
     * 管理员查询任意用户 2FA 状态（V2.0.26 可用）
     */
    @GetMapping("/users/{userId}/status")
    @PreAuthorize("hasAuthority('ROLE_PLATFORM_ADMIN') or @tenantAwareUtil.isPlatformAdmin()")
    public ApiResponse<Map<String, Object>> userStatus(@PathVariable Long userId) {
        return ApiResponse.ok(twoFactorService.getStatus(userId));
    }

    // ══════════════════════════════════════════════════════════════════
    // 修改类端点（V2.0.26 全部 501 Not Implemented · V2.0.27+ 实现）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 开启 2FA 设置 — 返回 501
     */
    @PostMapping("/setup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setup() {
        return notImplemented("setup");
    }

    /**
     * 验证首次设置 — 返回 501
     */
    @PostMapping("/verify-setup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifySetup(@RequestBody Map<String, String> body) {
        return notImplemented("verify-setup");
    }

    /**
     * 登录第二步验证 — 返回 501
     */
    @PostMapping("/verify-login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyLogin(@RequestBody Map<String, String> body) {
        return notImplemented("verify-login");
    }

    /**
     * 关闭 2FA — 返回 501
     */
    @PostMapping("/disable")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disable(@RequestBody Map<String, String> body) {
        return notImplemented("disable");
    }

    /**
     * 重生成 backup codes — 返回 501
     */
    @PostMapping("/backup-codes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backupCodes(@RequestBody Map<String, String> body) {
        return notImplemented("backup-codes");
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> notImplemented(String endpoint) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponse.<Map<String, Object>>fail(501,
                        "2FA " + endpoint + " 未实现。计划在 V2.0.27+ 落地。" +
                        "当前状态：SKELETON（schema 已建，service 抛 UnsupportedOperationException）。" +
                        "跟踪：登录安全阶段 P2.4 (V2.0.26 预留)。"));
    }
}
