package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 公司产品授权（租户→产品）
 *
 * 决定该租户购买了哪些产品，进而决定前端哪些产品"高亮"，哪些"灰色"
 */
@Data
@Entity
@Table(name = "company_product", uniqueConstraints = {
    @UniqueConstraint(name = "uk_company_product", columnNames = {"company_id", "product_id"})
}, indexes = {
    @Index(name = "idx_cp_company", columnList = "company_id"),
    @Index(name = "idx_cp_product", columnList = "product_id")
})
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "company_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
public class CompanyProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "license_start", nullable = false)
    private LocalDateTime licenseStart;

    @Column(name = "license_end")
    private LocalDateTime licenseEnd;

    @Column(name = "max_users")
    private Integer maxUsers = 10;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(length = 256)
    private String remark;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Status { ACTIVE, EXPIRED, SUSPENDED, CANCELLED }
}
