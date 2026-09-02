package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 用户双因素认证（2FA）实体 — V2.0.26 P2.4 阶段 D · 2FA 预留
 *
 * ⚠️ 状态：SKELETON / RESERVED · 未实际启用
 *
 * 此表 + 字段已在 V2.0.26 落地，但：
 * - TOTP secret 生成、QR 码渲染、验证码校验 → V2.0.27+ 实现
 * - TwoFactorService 的所有方法 → 抛 UnsupportedOperationException
 * - 登录流程不会触发 2FA challenge（V2.0.26 不会消费 mfa_required claim）
 * - 前端不展示 2FA 设置入口（V2.0.26 暂不开发 2FA UI）
 *
 * 设计意图（V2.0.27+ 落地参考）：
 * 1) 用户在设置页开启 2FA → 后端生成 TOTP secret（Base32）+ QR 码（otpauth URI）
 * 2) 用户用 Google Authenticator / Microsoft Authenticator 扫码 + 输入 6 位 code 验证
 * 3) 验证通过 → secret_encrypted 入库，enabled=true，生成 8 个一次性 backup codes
 * 4) 登录流程：密码 + TOTP code 双因素 → 颁发 mfa_verified=true 的 JWT
 *
 * 字段设计：
 * - secret_encrypted: AES-256-GCM 加密存储（密钥从 lingyao.jwt.secret 派生）
 *   即使 DB 泄漏，攻击者也拿不到原始 TOTP secret
 * - backup_codes_hash: 8 个一次性备份码（SHA-256 哈希），用于手机丢失时恢复
 * - last_used_at: 上次 TOTP 验证时间（防重放窗口）
 * - verified_at: 用户首次验证 2FA 的时间（区别于 created_at）
 *
 * 索引：
 * - user_id UNIQUE：一个用户只允许一份 2FA 记录
 * - enabled：查询"哪些用户启用了 2FA"
 */
@Data
@Entity
@Table(name = "user_2fa", indexes = {
        @Index(name = "idx_user_2fa_user", columnList = "user_id", unique = true),
        @Index(name = "idx_user_2fa_enabled", columnList = "enabled")
})
@EntityListeners(AuditingEntityListener.class)
public class User2FA {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * TOTP secret 加密存储（AES-256-GCM，密钥派生自 LINGYAO_JWT_SECRET）
     * 格式：Base64(IV || ciphertext || tag)
     * V2.0.27+ 实现加密逻辑
     */
    @Column(name = "secret_encrypted", columnDefinition = "TEXT")
    private String secretEncrypted;

    /**
     * 备份码（8 个一次性码的 SHA-256 哈希，JSON 数组字符串）
     * 例：["hash1", "hash2", ..., "hash8"]
     * V2.0.27+ 实现生成 + 验证
     */
    @Column(name = "backup_codes_hash", columnDefinition = "TEXT")
    private String backupCodesHash;

    /** 是否启用 */
    @Column(nullable = false)
    private Boolean enabled = false;

    /** 用户首次验证 2FA 的时间（开启 2FA 后必须先验证一次） */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** 上次 TOTP 验证时间（防重放窗口，30 秒） */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
