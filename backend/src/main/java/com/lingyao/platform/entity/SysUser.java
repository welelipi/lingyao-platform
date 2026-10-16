package com.lingyao.platform.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 系统用户实体（多租户用户）
 */
@Data
@Entity
@Table(name = "sys_user", indexes = {
    @Index(name = "idx_user_username", columnList = "username", unique = true),
    @Index(name = "idx_user_email", columnList = "email"),
    @Index(name = "idx_user_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String username;

    /**
     * V2.0.78.27 安全（L-04）：口令哈希绝不进 API 响应。
     *
     * 原先本字段没有 @JsonIgnore，AdminController#listUsers 直接返回 Page&lt;SysUser&gt;
     * 原样序列化 → BCrypt 哈希（含 salt 与 cost）随响应下发给前端。
     * 加 @JsonIgnore 后 Jackson 对该字段既不序列化也不反序列化。
     *
     * 反序列化侧无副作用（已核查）：
     * - 全项目无任何 Controller 以 SysUser 作为 @RequestBody（改密/建号走 DTO + setPasswordHash）；
     * - 前端（website/ 与 static/）无任何 passwordHash 消费点；
     * - 内部逻辑统一走 getPasswordHash()/setPasswordHash() Java 方法，不经 JSON。
     */
    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(length = 128)
    private String email;

    @Column(length = 32)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /** 是否平台超管（可跨租户管理） */
    @Column(name = "is_platform_admin", nullable = false)
    private Boolean isPlatformAdmin = false;

    /**
     * 是否已修改初始密码（私有化部署强制首登改密）
     * - true  : 已修改，正常登录
     * - false : 初始密码，登录后强制跳转改密页
     *
     * 默认 true（SAAS 老用户不受影响），私有化 data-private.sql 把 admin 设为 false
     */
    @Column(name = "password_changed", nullable = false)
    private Boolean passwordChanged = true;

    /**
     * 是否启用 2FA — V2.0.26 P2.4 预留字段
     *
     * V2.0.26：仅占位，登录流程不消费此字段
     * V2.0.27+：实际启用后，登录流程会检查此字段 + 调用 TwoFactorService.verifyLoginCode
     *
     * 注意：真正的 2FA 配置在 user_2fa 表（secret_encrypted + backup_codes），
     * 本字段仅是快速"是否启用"的冗余缓存（避免每次登录都查 user_2fa 表）
     */
    @Column(name = "two_factor_enabled", nullable = false)
    private Boolean twoFactorEnabled = false;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Column(name = "login_methods", length = 128)
    private String loginMethods = "PASSWORD";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum UserStatus { ACTIVE, LOCKED, DISABLED }
}
