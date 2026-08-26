package com.lingyao.platform.entity;

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
