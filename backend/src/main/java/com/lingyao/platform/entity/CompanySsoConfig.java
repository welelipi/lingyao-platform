package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 公司 SSO 配置（粒度：公司 × Provider）
 *
 * 一家公司可配多家 Provider（飞书+钉钉+企微可同时启用），由药企超管在主平台统一配置。
 *
 * V2.0.19+ 新增：
 * 1) SsoProvider 抽象层的配置实体
 * 2) KMS 加密存储 app_secret_enc（永远不出前端）
 * 3) dept_mapping 存 JSON 字符串（飞书部门 ID → BU 映射规则，可空）
 * 4) extra_config 存 JSON 字符串（给 SAML/OIDC 特殊字段扩展）
 */
@Data
@Entity
@Table(name = "company_sso_config", uniqueConstraints = {
    @UniqueConstraint(name = "uk_company_sso_provider", columnNames = {"company_id", "provider"})
}, indexes = {
    @Index(name = "idx_sso_company", columnList = "company_id"),
    @Index(name = "idx_sso_provider", columnList = "provider")
})
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "company_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
public class CompanySsoConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Provider provider;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** Feishu app_id / Dingtalk app_key / Wecom corp_id / OIDC client_id */
    @Column(name = "app_id", length = 256)
    private String appId;

    /**
     * KMS 加密存储（永远不返回前端）
     * Feishu app_secret / Dingtalk app_secret / Wecom secret / OIDC client_secret
     */
    @Column(name = "app_secret_enc", columnDefinition = "TEXT")
    private String appSecretEnc;

    /** 主平台回调地址（固定域名：https://lingyao.cn/api/sso/callback） */
    @Column(name = "redirect_uri", length = 512)
    private String redirectUri;

    /**
     * 不同 Provider 用不同 base URL
     * Feishu: https://open.feishu.cn
     * Dingtalk: https://oapi.dingtalk.com
     * Wecom: https://qyapi.weixin.qq.com
     * OIDC: issuer URL（如 https://login.microsoftonline.com/{tenant}/v2.0）
     */
    @Column(name = "base_url", length = 256)
    private String baseUrl;

    /** OAuth scope（飞书: "contact:user.id:readonly" 等） */
    @Column(length = 256)
    private String scope;

    /**
     * 飞书部门 ID → 凌瑶 BU 映射规则（JSON）
     * 例：{"dept_xxxx": "儿科BU", "dept_yyyy": "妇科BU"}
     * 空 = 传原始部门字符串给子平台
     */
    @Column(name = "dept_mapping", columnDefinition = "TEXT")
    private String deptMapping;

    /**
     * 扩展配置（JSON）—— SAML metadata URL、企业特殊字段等
     */
    @Column(name = "extra_config", columnDefinition = "TEXT")
    private String extraConfig;

    @Column(name = "created_by")
    private Long createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Provider {
        FEISHU,        // 飞书
        DINGTALK,      // 钉钉
        WECOM,         // 企业微信
        GENERIC_OIDC,  // 通用 OIDC（Okta / Auth0 / Azure AD）
        SAML           // SAML 2.0（阶段 2 实施，接口预留）
    }
}