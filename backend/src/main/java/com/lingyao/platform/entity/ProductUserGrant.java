package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 用户-产品授权（用户层 → 产品）
 *
 * 决定某用户在本公司内被授权哪些产品。
 * 例：A 药企·a_user1 只被授权 GEO，进入系统后只看到 GEO 高亮
 */
@Data
@Entity
@Table(name = "product_user_grant", uniqueConstraints = {
    @UniqueConstraint(name = "uk_pug_company_product_user", columnNames = {"company_id", "product_id", "user_id"})
}, indexes = {
    @Index(name = "idx_pug_user", columnList = "user_id"),
    @Index(name = "idx_pug_company_user", columnList = "company_id, user_id")
})
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "company_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
public class ProductUserGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum Status { ACTIVE, REVOKED }
}
