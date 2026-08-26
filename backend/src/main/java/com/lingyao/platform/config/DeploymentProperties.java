package com.lingyao.platform.config;

import com.lingyao.platform.entity.Company.DeploymentMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台级部署配置
 *
 * 与 Company 实体的 DeploymentMode（公司级）区分：
 * - Company.deploymentMode：描述"某个公司部署在什么环境"（SaaS 平台上可能有私有化子公司）
 * - DeploymentProperties.mode：描述"当前 platform 本身部署在什么环境"（决定行为差异）
 *
 * 设计原则：
 * - 默认值 = SaaS 形态（向后兼容，已有 dev 环境不变）
 * - 私有化部署通过 application-private.yml 覆盖
 * - 业务代码读取 DeploymentProperties 决定行为，不读 Company.deploymentMode
 *
 * 一次性预埋：业务代码后续只需注入本类即可
 */
@Component
@ConfigurationProperties(prefix = "lingyao.deployment")
public class DeploymentProperties {

    /**
     * 部署形态：SAAS / PRIVATE（默认 SAAS）
     */
    private DeploymentMode mode = DeploymentMode.SAAS;

    /**
     * 租户模式：SINGLE（私有化只服务 1 家公司）/ MULTI（共享多公司）
     * 默认 MULTI
     */
    private String tenantMode = "MULTI";

    /**
     * 注册策略：
     * - OPEN：新公司可自助注册（仅 SAAS 多租户有效）
     * - CLOSED：注册关闭（私有化默认）
     * - INVITE_ONLY：仅邀请链接注册
     * 默认 OPEN
     */
    private String registration = "OPEN";

    /**
     * 显示名称（用于 UI 标题 / 登录页脚 / 邮件签名）
     * SaaS 默认 "凌瑶智数"，私有化建议改 "客户公司名·凌瑶智数"
     */
    private String displayName = "凌瑶智数";

    /**
     * 私有化默认超管账号（首次启动时初始化使用）
     * 默认 admin / admin123（强制登录后改密）
     */
    private String bootstrapAdminUsername = "admin";
    private String bootstrapAdminPassword = "admin123";

    /**
     * 私有化默认公司 code
     * 默认 PRIVATE-CUSTOMER
     */
    private String bootstrapCompanyCode = "PRIVATE-CUSTOMER";

    // ────────────────────── 便捷方法（业务代码直接读这些） ──────────────────────

    public boolean isSaas() {
        return mode == DeploymentMode.SAAS;
    }

    public boolean isPrivate() {
        return mode == DeploymentMode.PRIVATE;
    }

    public boolean isMultiTenant() {
        return "MULTI".equalsIgnoreCase(tenantMode);
    }

    public boolean isSingleTenant() {
        return "SINGLE".equalsIgnoreCase(tenantMode);
    }

    public boolean isRegistrationOpen() {
        return "OPEN".equalsIgnoreCase(registration);
    }

    public boolean isRegistrationClosed() {
        return "CLOSED".equalsIgnoreCase(registration);
    }

    // ────────────────────── getters/setters ──────────────────────

    public DeploymentMode getMode() { return mode; }
    public void setMode(DeploymentMode mode) { this.mode = mode; }

    public String getTenantMode() { return tenantMode; }
    public void setTenantMode(String tenantMode) { this.tenantMode = tenantMode; }

    public String getRegistration() { return registration; }
    public void setRegistration(String registration) { this.registration = registration; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getBootstrapAdminUsername() { return bootstrapAdminUsername; }
    public void setBootstrapAdminUsername(String bootstrapAdminUsername) {
        this.bootstrapAdminUsername = bootstrapAdminUsername;
    }

    public String getBootstrapAdminPassword() { return bootstrapAdminPassword; }
    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    public String getBootstrapCompanyCode() { return bootstrapCompanyCode; }
    public void setBootstrapCompanyCode(String bootstrapCompanyCode) {
        this.bootstrapCompanyCode = bootstrapCompanyCode;
    }

    @Override
    public String toString() {
        return "DeploymentProperties{" +
                "mode=" + mode +
                ", tenantMode='" + tenantMode + '\'' +
                ", registration='" + registration + '\'' +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}