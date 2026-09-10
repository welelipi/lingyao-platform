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

    /**
     * V2.0.77.13 GEOM 棱镜套餐：basic / pro / flagship
     * 契约：/tmp/GEOM_quota_contract.md §1 + §2
     * - basic:    3 分类 / 每分类 10 品牌
     * - pro:      5 分类 / 每分类 20 品牌
     * - flagship: 10 分类 / 每分类 50 品牌
     * 默认 'basic'（缺省兜底，与契约 §2 R1 一致）
     * 列定义在 application 启动前手工 ALTER TABLE 已加（V2.0.77.13 DDL）
     */
    @Column(name = "geom_plan", length = 16)
    private String geomPlan = "basic";

    /**
     * V2.0.77.14 M4：年费档（人民币元/年）
     * 契约 §4 M4：定价与合同模板落三档年费（20/48/98 万），销售系统同步
     * 实际签约年费以商务合同为准；此字段是平台后台展示+合同模板生成的"建议价"
     * 缺省 200000（basic 档 20 万/年）
     */
    @Column(name = "annual_fee_yuan")
    private Integer annualFeeYuan = 200000;

    /**
     * V2.0.77.14 M4：合同模板档（NONE=未签 / BASIC=基础版 / PRO=专业版 / FLAGSHIP=旗舰版）
     * 与 geom_plan 同档位（但独立字段——geom_plan 是技术授权，contract_template 是商务合同档位）
     * 缺省 NONE（未签合同）
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "contract_template", length = 16)
    private ContractTemplate contractTemplate = ContractTemplate.NONE;

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
    /**
     * V2.0.10 状态枚举升级（3 种 → 5 种）：
     * - PENDING  : 开通中（刚创建，部署未完成）
     * - ACTIVE   : 已开通（正常使用）
     * - EXPIRED  : 已过期（许可证到期，scheduler 自动转）
     * - SUSPENDED: 已暂停（大超管手动）
     * - DELETED  : 已删除（软删除）
     */
    public enum CompanyStatus { PENDING, ACTIVE, EXPIRED, SUSPENDED, DELETED }
    /**
     * V2.0.77.14 M4：合同模板档位（与 GEOM 套餐配套，对应年费档）
     * - NONE      : 未签合同（试用 / 待签约）
     * - BASIC     : 基础版（20 万/年，对应 GEOM basic 3/10）
     * - PRO       : 专业版（48 万/年，对应 GEOM pro 5/20）
     * - FLAGSHIP  : 旗舰版（98 万/年，对应 GEOM flagship 10/50）
     */
    public enum ContractTemplate { NONE, BASIC, PRO, FLAGSHIP }
}
