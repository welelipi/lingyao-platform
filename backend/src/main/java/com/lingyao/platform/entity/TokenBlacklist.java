package com.lingyao.platform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JWT 黑名单实体 — V2.0.20 P1 阶段 A
 *
 * 用途：logout 后将当前 jti 写入，JwtAuthFilter 校验时拒绝
 * 字段：
 * - jti: JWT ID（UUID），唯一约束
 * - user_id / company_id: 便于审计 + 整批撤销
 * - expires_at: 黑名单过期时间 = JWT 原始 exp（过期后无需清理）
 * - reason: LOGOUT / ADMIN_REVOKE / PASSWORD_CHANGED / REFRESH_ROTATE / OTHER
 * - ip_address: 注销来源 IP（审计用）
 *
 * @since V2.0.20
 */
@Entity
@Table(name = "token_blacklist")
public class TokenBlacklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "jti", nullable = false, unique = true, length = 64)
    private String jti;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason = "LOGOUT";

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public TokenBlacklist() {}

    public TokenBlacklist(String jti, Long userId, Long companyId, LocalDateTime expiresAt,
                          String reason, String ipAddress) {
        this.jti = jti;
        this.userId = userId;
        this.companyId = companyId;
        this.expiresAt = expiresAt;
        this.reason = reason;
        this.ipAddress = ipAddress;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}