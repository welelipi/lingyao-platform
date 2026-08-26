package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 公司-用户关系（多对多）
 *
 * 决定用户在某公司内的角色（SUPER_ADMIN / OPERATOR / VIEWER）
 */
@Data
@Entity
@Table(name = "company_user", uniqueConstraints = {
    @UniqueConstraint(name = "uk_company_user", columnNames = {"company_id", "user_id"})
})
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "company_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
public class CompanyUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private CompanyRole role = CompanyRole.OPERATOR;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum CompanyRole { SUPER_ADMIN, OPERATOR, VIEWER }
    public enum Status { ACTIVE, REMOVED }
}
