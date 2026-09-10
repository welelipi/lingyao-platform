package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 认证安全审计事件 — V2.0.26 P2.4 阶段 A
 *
 * 与 CompanyAuditLog 的区别：
 * - CompanyAuditLog：业务操作审计（创建用户、修改产品授权等）
 * - AuthAuditEvent：安全事件审计（登录、登出、密码变更、CSRF 拒绝、限流命中、黑名单命中等）
 *
 * 设计要点：
 * - 独立表，不混入业务日志 → 安全分析查询更高效
 * - 必填：event_type + created_at
 * - 可空：user_id（登录失败时可能拿不到真实 user_id）、username（同上）
 * - 索引聚焦三类查询模式：① 按事件类型筛选 ② 按用户追踪 ③ 按 IP 排查
 *
 * 写入策略：
 * - 同步写入（与 AuditLogService 一致，参考 Bug-17 修复经验：@Async 会丢 ThreadLocal）
 * - 写入失败不影响主业务（try/catch 包裹，logger.error 不抛出）
 * - 默认保留 90 天（V2.0.27 加归档清理 job）
 *
 * 字段分类：
 * - 主体：event_type, user_id, username, company_id
 * - 上下文：ip_address, user_agent, request_path, request_method
 * - 结果：success, error_code, error_message
 * - 扩展：metadata (JSON 字符串，灵活存事件特有数据)
 */
@Data
@Entity
@Table(name = "auth_audit_event", indexes = {
        @Index(name = "idx_auth_audit_type_created", columnList = "event_type, created_at"),
        @Index(name = "idx_auth_audit_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_auth_audit_ip_created", columnList = "ip_address, created_at"),
        @Index(name = "idx_auth_audit_created", columnList = "created_at"),
        @Index(name = "idx_auth_audit_company", columnList = "company_id")
})
@EntityListeners(AuditingEntityListener.class)
public class AuthAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件类型（LOGIN_SUCCESS / LOGIN_FAIL / CSRF_REJECT 等） */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 48, nullable = false)
    private EventType eventType;

    /** 关联用户 ID（登录失败 / CSRF 拒绝时可能为 null） */
    @Column(name = "user_id")
    private Long userId;

    /** 关联用户名（登录失败时为尝试的用户名，便于排查） */
    @Column(length = 64)
    private String username;

    /** 关联公司 ID（多租户隔离） */
    @Column(name = "company_id")
    private Long companyId;

    /** 客户端 IP（X-Forwarded-For → X-Real-IP → remoteAddr） */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** User-Agent（截断到 512 字符） */
    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** 请求路径（如 /api/admin/users） */
    @Column(name = "request_path", length = 256)
    private String requestPath;

    /** 请求方法（GET / POST / PUT / DELETE / PATCH） */
    @Column(name = "request_method", length = 8)
    private String requestMethod;

    /** 是否成功（true=成功 / false=失败） */
    @Column(nullable = false)
    private Boolean success = true;

    /** 错误码（如 ACCOUNT_LOCKED / INVALID_PASSWORD / CSRF_TOKEN_INVALID / RATE_LIMIT_EXCEEDED） */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    /** 错误消息（人类可读，会脱敏） */
    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /**
     * 扩展元数据（JSON 字符串，事件特有数据）
     * 例：
     * - LOGIN_FAIL：{"attemptsRemaining": 3, "lockUntil": "2026-09-02T10:30:00"}
     * - REFRESH_REPLAY：{"revokedTokensCount": 5, "oldTokenId": 123}
     * - CSRF_REJECT：{"cookiePresent": false, "headerPresent": false}
     * - RATE_LIMIT_HIT：{"endpoint": "login", "windowMs": 60000, "limit": 5}
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 安全事件类型枚举
     *
     * 命名规范：{分类}_{动作}
     * - LOGIN_*: 登录相关（成功/失败/锁定/登出）
     * - TOKEN_*: Token 生命周期（刷新/重放/黑名单命中）
     * - PASSWORD_*: 密码变更（主动改密/重置/忘记密码请求）
     * - CSRF_*: CSRF 防护（拒绝）
     * - RATE_LIMIT_*: 限流（命中）
     * - PERMISSION_*: 权限（拒绝）
     */
    public enum EventType {
        // ──── 登录相关 ────
        LOGIN_SUCCESS,             // 登录成功
        LOGIN_FAIL,                // 登录失败（密码错）
        LOGIN_LOCKED,              // 登录被锁定（Bug-09）
        LOGIN_DISABLED,            // 登录被拒（账号已停用）
        LOGIN_COMPANY_EXPIRED,     // 登录被拒（公司许可过期）

        LOGOUT,                    // 主动登出

        // ──── Token 生命周期 ────
        TOKEN_REFRESH,             // refresh_token 滑动续期成功
        TOKEN_REFRESH_REPLAY,      // refresh_token 重放检测
        TOKEN_REFRESH_INVALID,     // refresh_token 无效
        TOKEN_BLACKLIST_HIT,       // access_token 命中黑名单（已登出但前端复用）

        // ──── 密码相关 ────
        PASSWORD_CHANGED,          // 主动改密
        PASSWORD_RESET_REQUESTED,  // 申请重置密码（forgot-password）
        PASSWORD_RESET,            // 重置密码成功
        PASSWORD_RESET_INVALID,    // 重置 token 无效/过期/已用
        PASSWORD_RESET_USER_NOT_FOUND,  // 重置请求的用户不存在（可疑枚举）

        // ──── 防护层 ────
        CSRF_REJECT,               // CSRF token 校验失败
        RATE_LIMIT_HIT,            // 限流命中
        PERMISSION_DENIED,         // 权限不足（@PreAuthorize 拒绝等）

        // ──── OTP 验证（V2.0.77.17 R5-05 / SEC-04：爆破尝试对安全事件面板可见）────
        OTP_VERIFY_FAIL,           // 邮箱验证码校验失败
        OTP_LOCKED                 // 验证码连错 5 次触发锁定
    }
}
