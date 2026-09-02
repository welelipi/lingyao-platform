package com.lingyao.platform.service;

import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.entity.User2FA;
import com.lingyao.platform.repository.SysUserRepository;
import com.lingyao.platform.repository.User2FARepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 双因素认证服务 — V2.0.26 P2.4 阶段 D · 2FA 预留 SKELETON
 *
 * ⚠️ 状态：SKELETON / RESERVED · 未实际启用
 *
 * 所有真实方法抛 UnsupportedOperationException，提醒调用方 V2.0.27+ 才有完整实现。
 *
 * V2.0.27+ 计划实现：
 * 1) generateSetup(userId) → 返回 otpauth:// URI + Base32 secret + QR 码 base64
 * 2) verifySetup(userId, code) → 验证用户首次输入的 6 位 TOTP code，标 enabled=true
 * 3) verifyLogin(userId, code) → 登录流程第二步验证
 * 4) verifyBackupCode(userId, code) → 一次性备份码验证
 * 5) disable(userId, password) → 输入密码关闭 2FA
 * 6) regenerateBackupCodes(userId, password) → 重生成 8 个新 backup codes
 *
 * 依赖：
 * - secret 加密：AES-256-GCM，密钥派生自 LINGYAO_JWT_SECRET
 * - TOTP 实现：dev.zacteam:googleauth 或自实现 HMAC-SHA1 (RFC 6238)
 * - QR 码：com.google.zxing:core + qrcode-javase
 *
 * 安全考虑：
 * - secret 仅在生成 setup 时回显一次，之后永不返回明文
 * - backup_codes_hash 用 SHA-256，明文仅生成时回显一次
 * - last_used_at 防 30 秒重放窗口
 * - 启用 2FA 后强制用户在 24h 内验证一次，否则自动 disabled
 */
@Service
public class TwoFactorService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);

    @Autowired
    private User2FARepository user2FARepository;

    @Autowired
    private SysUserRepository userRepo;

    // ══════════════════════════════════════════════════════════════════
    // 状态查询方法（已可用 · 仅读 DB）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 查询用户 2FA 状态（V2.0.26 可用 · 仅读）
     *
     * @param userId 用户 ID
     * @return 状态 Map：{enabled, verified, createdAt, ...}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStatus(Long userId) {
        Optional<User2FA> opt = user2FARepository.findByUserId(userId);
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("userId", userId);
        status.put("exists", opt.isPresent());
        if (opt.isPresent()) {
            User2FA u2fa = opt.get();
            status.put("enabled", u2fa.getEnabled());
            status.put("verified", u2fa.getVerifiedAt() != null);
            status.put("verifiedAt", u2fa.getVerifiedAt());
            status.put("lastUsedAt", u2fa.getLastUsedAt());
            status.put("createdAt", u2fa.getCreatedAt());
        }
        status.put("implementationStatus", "RESERVED");
        status.put("availableInVersion", "V2.0.27+");
        return status;
    }

    /**
     * 用户是否启用了 2FA（供 login 流程判断 · V2.0.27+ 真正使用）
     *
     * V2.0.26 暂未在 login 流程调用，仅作为预留 API 暴露给前端
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(Long userId) {
        return user2FARepository.existsByUserIdAndEnabledTrue(userId);
    }

    // ══════════════════════════════════════════════════════════════════
    // 真实方法（SKELETON · 抛 UnsupportedOperationException）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 开启 2FA 设置流程
     *
     * V2.0.26 抛 UnsupportedOperationException
     * V2.0.27+ 计划：生成 TOTP secret + QR 码，返回给前端展示
     */
    @Transactional
    public Map<String, Object> generateSetup(Long userId) {
        throw new UnsupportedOperationException(
                "2FA 设置生成未实现。计划在 V2.0.27+ 落地。\n" +
                "跟踪：登录安全阶段 P2.4 (V2.0.26 预留 schema + skeleton)");
    }

    /**
     * 验证用户首次输入的 6 位 TOTP code（开启流程的最后一步）
     */
    @Transactional
    public void verifySetup(Long userId, String code) {
        throw new UnsupportedOperationException(
                "2FA 设置验证未实现。计划在 V2.0.27+ 落地。");
    }

    /**
     * 登录流程第二步验证（密码 + TOTP code）
     */
    @Transactional
    public boolean verifyLoginCode(Long userId, String code) {
        throw new UnsupportedOperationException(
                "2FA 登录验证未实现。计划在 V2.0.27+ 落地。");
    }

    /**
     * 一次性备份码验证（手机丢失场景）
     */
    @Transactional
    public boolean verifyBackupCode(Long userId, String code) {
        throw new UnsupportedOperationException(
                "2FA 备份码验证未实现。计划在 V2.0.27+ 落地。");
    }

    /**
     * 关闭 2FA（需要密码二次确认）
     */
    @Transactional
    public void disable(Long userId, String password) {
        throw new UnsupportedOperationException(
                "2FA 关闭未实现。计划在 V2.0.27+ 落地。");
    }

    /**
     * 重新生成 8 个 backup codes（需要密码二次确认）
     */
    @Transactional
    public String[] regenerateBackupCodes(Long userId, String password) {
        throw new UnsupportedOperationException(
                "2FA 备份码重生成未实现。计划在 V2.0.27+ 落地。");
    }

    /**
     * 验证用户密码（用于 2FA 敏感操作的二次确认）
     */
    public boolean verifyPassword(Long userId, String password) {
        SysUser user = userRepo.findById(userId).orElse(null);
        if (user == null) return false;
        // BCryptPasswordEncoder 已注入到 AuthService，这里仅作 hook 占位
        // V2.0.27+ 实现：注入 PasswordEncoder，调用 matches()
        return false;
    }
}
