package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 公司（租户）实体
 *
 * 复用 OEG 项目决策：
 * - 租户模型：A：一公司一账号
 * - 私有化部署：deployment_mode = PRIVATE
 * - 许可证模式：license_plan
 */
@Data
@Entity
@Table(name = "company", indexes = {
    @Index(name = "idx_company_code", columnList = "code", unique = true),
    @Index(name = "idx_company_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(unique = true, length = 64)
    private String code;

    /** 部署模式：SAAS（共享云）/ PRIVATE（私有化部署） */
    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_mode", length = 32, nullable = false)
    private DeploymentMode deploymentMode = DeploymentMode.SAAS;

    /** 许可证等级：TRIAL / STANDARD / ENTERPRISE */
    @Enumerated(EnumType.STRING)
    @Column(name = "license_plan", length = 32)
    private LicensePlan licensePlan = LicensePlan.STANDARD;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private CompanyStatus status = CompanyStatus.ACTIVE;

    @Column(name = "max_users")
    private Integer maxUsers = 10;

    @Column(name = "contact_email", length = 128)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(length = 256)
    private String address;

    @Column(name = "license_start")
    private LocalDateTime licenseStart;

    @Column(name = "license_end")
    private LocalDateTime licenseEnd;

    @Column(length = 512)
    private String remark;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum DeploymentMode { SAAS, PRIVATE }
    public enum LicensePlan { TRIAL, STANDARD, ENTERPRISE }
    public enum CompanyStatus { ACTIVE, SUSPENDED, DELETED }
}
