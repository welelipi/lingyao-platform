package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 审计日志归档表 — V2.0.42 P2.4
 *
 * company_audit_log 的归档镜像（结构一致，无租户 Filter —— 归档数据仅供平台超管合规查询）。
 * 归档策略：每日 02:30 把 N 天前（默认 90）记录搬入本表并从原表删除。
 * 查询：归档数据暂只落库不提供 UI（合规留存 90 天以上即可，需要时再开查询接口）。
 */
@Data
@Entity
@Table(name = "company_audit_log_archive", indexes = {
        @Index(name = "idx_audit_arch_company_created", columnList = "company_id, created_at"),
        @Index(name = "idx_audit_arch_created", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class CompanyAuditLogArchive {

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

    /** 原表写入时间（归档时间 = archived_at） */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @CreatedDate
    @Column(name = "archived_at", nullable = false, updatable = false)
    private LocalDateTime archivedAt;
}
