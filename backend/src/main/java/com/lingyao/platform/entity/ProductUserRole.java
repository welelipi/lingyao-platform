package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 用户-产品-角色 关系（产品内角色层）
 *
 * 决定某用户在某产品内的具体角色
 * 例：A 药企·张三 在 GEO 内是 super_admin
 */
@Data
@Entity
@Table(name = "product_user_role", uniqueConstraints = {
    @UniqueConstraint(name = "uk_pur_company_product_user", columnNames = {"company_id", "product_id", "user_id"})
}, indexes = {
    @Index(name = "idx_pur_user", columnList = "user_id"),
    @Index(name = "idx_pur_company_product", columnList = "company_id, product_id")
})
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "company_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
public class ProductUserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
