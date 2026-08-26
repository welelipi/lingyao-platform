package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 用户邀请链接 — OEG 决策要求
 *
 * 流程：
 * 1. 管理员在超管后台创建邀请（指定公司 + 目标产品 + 角色）
 * 2. 系统生成一次性 token（有效期 7 天）
 * 3. 通过推送通道将 invite URL 发给被邀请人
 * 4. 被邀请人访问 GET /api/invitations/token/{token} 校验有效性
 * 5. 提交 POST /api/invitations/redeem 完成注册（需新填用户名/密码）
 * 6. 链接一次性使用 → 标记 CONSUMED
 */
@Data
@Entity
@Table(name = "invitation", indexes = {
    @Index(name = "idx_inv_token", columnList = "token", unique = true),
    @Index(name = "idx_inv_company", columnList = "company_id")
})
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "company_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一次性随机 token（32 字节 URL-safe base64）*/
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /** 被邀请人邮箱（可选，写了则注册时强制匹配） */
    @Column(length = 128)
    private String invitedEmail;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** 授权的产品 code（GEO/HPD/AIDD/POR，单值，可多张邀请卡多次授权） */
    @Column(name = "product_code", nullable = false, length = 16)
    private String productCode;

    /** 角色 code（OPERATOR / SENIOR_OPERATOR / SUPER_ADMIN） */
    @Column(name = "role_code", nullable = false, length = 32)
    private String roleCode;

    /** 邀请发起人 */
    @Column(name = "invited_by_user_id", nullable = false)
    private Long invitedByUserId;

    @Column(length = 256)
    private String remark;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "consumed_by_user_id")
    private Long consumedByUserId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum Status { PENDING, CONSUMED, EXPIRED, REVOKED }

    public boolean isUsable() {
        return status == Status.PENDING && LocalDateTime.now().isBefore(expiresAt);
    }
}
