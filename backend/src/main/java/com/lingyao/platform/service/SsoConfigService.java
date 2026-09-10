package com.lingyao.platform.service;

import com.lingyao.platform.entity.CompanySsoConfig;
import com.lingyao.platform.entity.SsoLoginAudit;
import com.lingyao.platform.repository.CompanySsoConfigRepository;
import com.lingyao.platform.repository.SsoLoginAuditRepository;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.security.TenantContext;
import com.lingyao.platform.sso.SsoSecretCipher;
import com.lingyao.platform.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * SSO 配置业务逻辑 — 2026-09-01 V2.0.19 新增
 *
 * 职责：
 * 1) 公司超管 CRUD SSO 配置（飞书/钉钉/企微/GenericOidc/SAML）
 * 2) KMS 加密 app_secret（永远不出前端）
 * 3) audit_log 记录所有配置变更 + 所有 SSO 登录
 * 4) 提供解密后的 config 给 OAuth callback 使用
 *
 * 越权防护：调用方必须先通过 CompanyAdminController.requireTenantAdmin() 守卫
 */
@Service
public class SsoConfigService {

    @Autowired private CompanySsoConfigRepository configRepo;
    @Autowired private SsoLoginAuditRepository auditRepo;
    @Autowired private SsoSecretCipher cipher;
    @Autowired private TenantContext tenantContext;
    @Autowired private AuditLogService auditLogService;

    // ═══════════════════════ 配置 CRUD ═══════════════════════

    /**
     * 列出当前公司的所有 SSO 配置（脱敏：app_secret_enc 不返回）
     */
    public List<Map<String, Object>> listConfigs(Long currentUserId, Long companyId) {
        List<CompanySsoConfig> configs = configRepo.findByCompanyId(companyId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CompanySsoConfig c : configs) {
            result.add(toMap(c, false));
        }
        return result;
    }

    /**
     * 保存/更新 SSO 配置（upsert）
     *
     * @param provider     FEISHU/DINGTALK/WECOM/GENERIC_OIDC/SAML
     * @param input        用户输入的 app_id / app_secret / redirect_uri / base_url / scope / dept_mapping / extra_config / enabled
     * @param currentUserId 当前操作者（写入 created_by / audit_log）
     */
    @Transactional
    public Map<String, Object> upsertConfig(Long currentUserId, Long companyId,
                                             CompanySsoConfig.Provider provider,
                                             Map<String, Object> input) {
        Optional<CompanySsoConfig> existing = configRepo.findByCompanyIdAndProvider(companyId, provider);

        CompanySsoConfig c = existing.orElseGet(CompanySsoConfig::new);
        c.setCompanyId(companyId);
        c.setProvider(provider);
        c.setEnabled(Boolean.TRUE.equals(input.get("enabled")) || input.get("enabled") == null ? true : (Boolean) input.get("enabled"));

        if (input.get("app_id") != null) {
            c.setAppId(((String) input.get("app_id")).trim());
        }
        if (input.get("app_secret") != null) {
            // 用户输入的明文 secret → KMS 加密
            String plainSecret = ((String) input.get("app_secret")).trim();
            if (!plainSecret.isEmpty()) {
                c.setAppSecretEnc(cipher.encrypt(plainSecret));
            }
        }
        if (input.get("redirect_uri") != null) {
            c.setRedirectUri(((String) input.get("redirect_uri")).trim());
        }
        if (input.get("base_url") != null) {
            c.setBaseUrl(((String) input.get("base_url")).trim());
        }
        if (input.get("scope") != null) {
            c.setScope(((String) input.get("scope")).trim());
        }
        if (input.get("dept_mapping") != null) {
            c.setDeptMapping((String) input.get("dept_mapping"));
        }
        if (input.get("extra_config") != null) {
            c.setExtraConfig((String) input.get("extra_config"));
        }

        if (existing.isEmpty()) {
            c.setCreatedBy(currentUserId);
        }

        CompanySsoConfig saved = configRepo.save(c);

        // V2.0.77.16（R5-03 / SEC-05 修复）：SSO 配置变更入审计（prev 零审计）。
        // 记 operator+provider+是否变更 secret（secret 本体绝不入审计文案）。
        boolean secretChanged = input.get("app_secret") != null
                && !((String) input.get("app_secret")).isBlank();
        auditLogService.record("SSO_CONFIG_UPSERT", "SSO_CONFIG", String.valueOf(saved.getId()),
                (existing.isPresent() ? "更新" : "新建") + " SSO 配置: " + provider
                        + (secretChanged ? "（含密钥变更）" : "（未变更密钥）"));

        return toMap(saved, false);
    }

    /**
     * 删除 SSO 配置（不可恢复，需二次确认）
     */
    @Transactional
    public void deleteConfig(Long currentUserId, Long companyId, CompanySsoConfig.Provider provider) {
        // V2.0.77.16（R5-03 / SEC-05 修复）：删除前先取 id 用于审计（删除后查不到）
        Long configId = configRepo.findByCompanyIdAndProvider(companyId, provider)
                .map(CompanySsoConfig::getId).orElse(null);
        configRepo.deleteByCompanyIdAndProvider(companyId, provider);
        // V2.0.77.16（R5-03）：SSO 删除入审计（影响该公司全员登录方式，必须留痕）
        auditLogService.record("SSO_CONFIG_DELETE", "SSO_CONFIG",
                configId == null ? "-" : String.valueOf(configId),
                "删除 SSO 配置: " + provider);
    }

    /**
     * 获取解密后的配置（仅给 OAuth callback 内部用）
     * 不暴露给 controller
     */
    public CompanySsoConfig getDecryptedConfig(Long companyId, CompanySsoConfig.Provider provider) {
        CompanySsoConfig c = configRepo.findByCompanyIdAndProvider(companyId, provider)
            .orElseThrow(() -> new IllegalArgumentException("SSO 配置不存在: " + provider));
        if (c.getAppSecretEnc() != null && !c.getAppSecretEnc().isEmpty()) {
            // ⚠️ 把明文放回 appSecretEnc 字段供 Provider 使用（字段复用）
            c.setAppSecretEnc(cipher.decrypt(c.getAppSecretEnc()));
        }
        return c;
    }

    // ═══════════════════════ Audit Log ═══════════════════════

    @Transactional
    public SsoLoginAudit logPending(Long companyId, CompanySsoConfig.Provider provider,
                                     String subProductCode, String clientIp, String userAgent) {
        SsoLoginAudit log = new SsoLoginAudit();
        log.setCompanyId(companyId);
        log.setProvider(provider);
        log.setSubProductCode(subProductCode);
        log.setStatus(SsoLoginAudit.Status.PENDING);
        log.setClientIp(clientIp);
        log.setUserAgent(userAgent);
        return auditRepo.save(log);
    }

    @Transactional
    public void logSuccess(Long auditId, String employeeId, String providerUserId,
                           String displayName, String deptId) {
        auditRepo.findById(auditId).ifPresent(log -> {
            log.setStatus(SsoLoginAudit.Status.SUCCESS);
            log.setEmployeeId(employeeId);
            log.setProviderUserId(providerUserId);
            log.setDisplayName(displayName);
            log.setDeptId(deptId);
            auditRepo.save(log);
        });
    }

    @Transactional
    public void logFailed(Long auditId, String errorMessage) {
        auditRepo.findById(auditId).ifPresent(log -> {
            log.setStatus(SsoLoginAudit.Status.FAILED);
            log.setErrorMessage(errorMessage);
            auditRepo.save(log);
        });
    }

    /**
     * 查询 SSO 登录审计日志（按公司）
     */
    public Map<String, Object> listAuditLogs(Long companyId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SsoLoginAudit> p = auditRepo.findByCompanyIdOrderByCreatedAtDesc(companyId, pageable);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", p.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SsoLoginAudit log : p.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", log.getId());
            m.put("provider", log.getProvider());
            m.put("employee_id", log.getEmployeeId());
            m.put("provider_user_id", log.getProviderUserId());
            m.put("display_name", log.getDisplayName());
            m.put("dept_id", log.getDeptId());
            m.put("sub_product_code", log.getSubProductCode());
            m.put("status", log.getStatus());
            m.put("client_ip", log.getClientIp());
            m.put("error_message", log.getErrorMessage());
            m.put("created_at", log.getCreatedAt());
            items.add(m);
        }
        result.put("items", items);
        return result;
    }

    // ═══════════════════════ 内部方法 ═══════════════════════

    /**
     * entity → Map（脱敏模式：不返回 app_secret_enc）
     */
    private Map<String, Object> toMap(CompanySsoConfig c, boolean includeSecret) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("company_id", c.getCompanyId());
        m.put("provider", c.getProvider());
        m.put("enabled", c.getEnabled());
        m.put("app_id", c.getAppId());
        m.put("has_secret", c.getAppSecretEnc() != null && !c.getAppSecretEnc().isEmpty());
        m.put("redirect_uri", c.getRedirectUri());
        m.put("base_url", c.getBaseUrl());
        m.put("scope", c.getScope());
        m.put("dept_mapping", c.getDeptMapping());
        m.put("extra_config", c.getExtraConfig());
        m.put("created_at", c.getCreatedAt());
        m.put("updated_at", c.getUpdatedAt());
        return m;
    }

    // ═══════════════════════ Controller 可见的辅助方法 ═══════════════════════

    /**
     * 单条配置转 Map（Controller 调）
     */
    public Map<String, Object> toPublicMap(CompanySsoConfig c) {
        return toMap(c, false);
    }

    /**
     * 暴露 repository 给 Controller（避免每个方法都依赖注入 2 次）
     */
    public CompanySsoConfigRepository configRepo() {
        return configRepo;
    }
}