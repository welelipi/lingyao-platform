package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 审计日志实体 — Bug-17 修复
 * 记录谁在何时对什么资源做了什么操作
 */
@Data
@Entity
@Table(name = "company_audit_log", indexes = {
        @Index(name = "idx_audit_company_created", columnList = "company_id, created_at"),
        @Index(name = "idx_audit_user", columnList = "actor_user_id"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "company_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
public class CompanyAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_username", length = 64)
    private String actorUsername;

    @Column(name = "action", length = 64, nullable = false)
    private String action;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
