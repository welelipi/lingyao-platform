package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 子任务 / 产品授权审批流 — V2.0.19 W6 阶段 2.5 B1 集中审批后端
 *
 * 适用场景（任一公司超管审批流）：
 * - SUBTASK_INVOKE: 员工调用某个子任务，需要超管授权
 * - PRODUCT_GAR: 申请某个产品的访问权限
 * - SSO_LINK_REQUEST: 申请生成专属链接
 * - OTHER: 其它通用审批
 *
 * 字段设计原则：
 * - type + payload_json 通用化：保留扩展能力，避免每种审批流单独建表
 * - requester_* 与 approver_* 平铺：前端列表显示方便
 * - status 枚举 PENDING/APPROVED/REJECTED：单层审批，不嵌套
 */
@Data
@Entity
@Table(name = "sub_task_approval", indexes = {
    @Index(name = "idx_approval_company_status", columnList = "company_id, status"),
    @Index(name = "idx_approval_product", columnList = "product_id"),
    @Index(name = "idx_approval_type", columnList = "type"),
    @Index(name = "idx_approval_requester", columnList = "requester_id")
})
@EntityListeners(AuditingEntityListener.class)
public class SubTaskApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 申请所属公司（前端按此过滤：只看本公司审批） */
    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** 目标产品（可空：非产品相关审批） */
    @Column(name = "product_id")
    private Long productId;

    /** 审批类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Type type = Type.OTHER;

    /** 申请人 */
    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "requester_username", nullable = false, length = 64)
    private String requesterUsername;

    /** 申请说明 */
    @Column(columnDefinition = "TEXT")
    private String reason;

    /** 审批状态 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Status status = Status.PENDING;

    /** 审批人（通过/拒绝时填） */
    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "approver_username", length = 64)
    private String approverUsername;

    /** 审批时间 */
    @Column(name = "approve_time")
    private LocalDateTime approveTime;

    /** 拒绝理由 */
    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** 附加字段 JSON（保留扩展） */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Type {
        SUBTASK_INVOKE,  // 员工调用子任务授权
        PRODUCT_GRANT,   // 申请产品访问权限
        SSO_LINK_REQUEST,// 申请生成专属链接
        OTHER            // 其它通用审批
    }

    public enum Status { PENDING, APPROVED, REJECTED }
}