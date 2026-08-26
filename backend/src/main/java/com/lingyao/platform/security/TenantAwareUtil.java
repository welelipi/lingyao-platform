package com.lingyao.platform.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * 租户隔离工具类 — Phase 2.5 数据隔离加固
 *
 * 实际隔离由 TenantStatementInspector（SQL 层拦截器）执行
 * 本类保留 enableFilter/disableFilter 旧 API 以兼容 JwtAuthFilter 调用
 *
 * StatementInspector 在 Hibernate 生成 SQL 时拦截并强制注入 WHERE company_id = ?
 * - 不依赖 Session 生命周期（更可靠）
 * - 只对租户数据表生效
 * - 平台超管豁免
 *
 * 配置位置：application.yml → spring.jpa.properties.hibernate.session_factory.statement_inspector
 */
@Component
public class TenantAwareUtil {

    public static final String FILTER_NAME = "tenantFilter";
    public static final String PARAM_NAME = "tenantId";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 启用租户隔离过滤器（兼容旧 API）
     * @deprecated 实际隔离由 TenantStatementInspector 执行，本方法保留仅为兼容
     */
    @Deprecated
    public void enableTenantFilter(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        try {
            Session session = entityManager.unwrap(Session.class);
            org.hibernate.Filter filter = session.enableFilter(FILTER_NAME);
            filter.setParameter(PARAM_NAME, tenantId);
        } catch (Exception e) {
            // Session 还未创建时忽略（StatementInspector 会兜底）
        }
    }

    /**
     * 禁用租户隔离过滤器（兼容旧 API）
     */
    public void disableTenantFilter() {
        try {
            Session session = entityManager.unwrap(Session.class);
            if (session.isOpen()) {
                session.disableFilter(FILTER_NAME);
            }
        } catch (Exception e) {
            // ignore
        }
    }
}