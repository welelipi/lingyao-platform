package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * SSO 登录审计日志
 *
 * 记录每次 SSO 跳转/回调/登录结果（含失败），用于：
 * 1) 药企超管审计员工通过什么渠道登录
 * 2) 主平台运维定位 OAuth 集成问题
 * 3) 安全合规（谁什么时候从哪个 IP 尝试登录）
 *
 * V2.0.19+ 新增
 */
@Data
@Entity
@Table(name = "sso_login_audit", indexes = {
    @Index(name = "idx_sso_audit_company", columnList = "company_id"),
    @Index(name = "idx_sso_audit_provider", columnList = "provider"),
    @Index(name = "idx_sso_audit_status", columnList = "status"),
    @Index(name = "idx_sso_audit_created", columnList = "created_at")
})
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "company_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
public class SsoLoginAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id")
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CompanySsoConfig.Provider provider;

    /** 主平台员工 ID（飞书 user_id / 钉钉 userid / 企微 userid 映射后） */
    @Column(name = "employee_id", length = 128)
    private String employeeId;

    /** 三方原始 ID（飞书 open_id / 钉钉 unionid / 企微 userid） */
    @Column(name = "provider_user_id", length = 256)
    private String providerUserId;

    /** 员工姓名（从三方 user_info 拿） */
    @Column(length = 128)
    private String displayName;

    /** 飞书部门 ID / 钉钉 dept_id / 企微 department */
    @Column(name = "dept_id", length = 128)
    private String deptId;

    /** 跳转目标子产品 code（GEOM/MPDM/AIDD/DINFO/PORM/MED-PPT） */
    @Column(name = "sub_product_code", length = 32)
    private String subProductCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum Status {
        PENDING,          // OAuth 已发起，等待 callback
        SUCCESS,          // 登录成功（含首次扫码 PENDING_REVIEW）
        FAILED            // 登录失败（token 验签失败 / 用户拒绝授权等）
    }
}