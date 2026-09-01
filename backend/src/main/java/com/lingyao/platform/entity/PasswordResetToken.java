package com.lingyao.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * PasswordResetToken — V2.0.21 P1.5 阶段 B
 *
 * 设计要点（参照 Django / Laravel 业界主流）：
 * - token_hash 唯一（不存明文 token，DB 泄露不可重放）
 * - expires_at 默认 1 小时（短 TTL，邮件场景够用）
 * - used_at 标记是否已用（一次性 token）
 * - failed_attempts 计数（V2.0.22+ 防爆破，前端加 ratelimit）
 *
 * 工作流：
 * 1) forgot-password(user.email) → 生成 + 邮件发送 reset_token（明文，仅一次回显给 API 返回，不入库）
 * 2) 用户点邮件链接 → /api/auth/reset-password?token=xxx
 * 3) 后端 hash 查 DB → 校验 + 修改密码 + 撤销所有 refresh_token
 */
@Entity
@Table(name = "password_reset_token", indexes = {
        @Index(name = "idx_reset_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_reset_user_active", columnList = "user_id, used_at, expires_at"),
        @Index(name = "idx_reset_expires", columnList = "expires_at")
})
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256(plainToken) — 不存明文 */
    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    /** 过期时间（默认 1 小时）*/
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 使用时间 */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PasswordResetToken() {}

    public PasswordResetToken(Long userId, String tokenHash, LocalDateTime expiresAt, String ipAddress) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isActive() {
        return !isExpired() && !isUsed();
    }
}