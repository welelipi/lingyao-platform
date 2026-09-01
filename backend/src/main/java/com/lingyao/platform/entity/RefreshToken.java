package com.lingyao.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * RefreshToken — V2.0.21 P1.4 阶段 B
 *
 * 设计要点：
 * - token_hash: 不存明文 token，存 SHA-256 hash（数据库泄露也不可重放）
 * - replaced_by_id: 滑动续期链（旧 token 被换后记录新 token id，防重放审计）
 * - revoked_at: 用户主动 / 管理员撤销时间
 * - ip_address + user_agent: 异常登录检测（V2.0.22+ 接入）
 *
 * 滑动续期策略：
 * - 每个 refresh_token 只能用一次（用完即 mark used_at）
 * - refresh 同时发新 access_token + 新 refresh_token，旧 token 写入 token_blacklist
 * - 已 used 的 token 再次出现 → 整条链全部撤销（防 token 泄漏重放）
 */
@Entity
@Table(name = "refresh_token", indexes = {
        @Index(name = "idx_refresh_user_active", columnList = "user_id, used_at, expires_at"),
        @Index(name = "idx_refresh_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_refresh_expires", columnList = "expires_at")
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256(plainToken) — 不存明文 */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** 过期时间（默认 30 天）*/
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 使用时间（每次 refresh 滑动一次，标 used_at）*/
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** 被哪个 token 替换（滑动续期链追踪）*/
    @Column(name = "replaced_by_id")
    private Long replacedById;

    /** 撤销时间（用户 logout / 改密 / 管理员撤销）*/
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 撤销原因：LOGOUT / PASSWORD_CHANGED / ADMIN_REVOKE / REFRESH_REPLAY_DETECTED */
    @Column(name = "revoked_reason", length = 32)
    private String revokedReason;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RefreshToken() {}

    public RefreshToken(Long userId, String tokenHash, LocalDateTime expiresAt,
                        String ipAddress, String userAgent) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    // ─── Getters / Setters ────────────────────────────

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

    public Long getReplacedById() { return replacedById; }
    public void setReplacedById(Long replacedById) { this.replacedById = replacedById; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }

    public String getRevokedReason() { return revokedReason; }
    public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // ─── 辅助判断 ──────────────────────────────

    /** 是否过期 */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /** 是否已被使用（滑动续期核心判定）*/
    public boolean isUsed() {
        return usedAt != null;
    }

    /** 是否已撤销 */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** 是否可用（未过期 + 未使用 + 未撤销）*/
    public boolean isActive() {
        return !isExpired() && !isUsed() && !isRevoked();
    }
}