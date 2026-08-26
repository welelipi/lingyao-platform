/**
 * 凌瑶智数 · 实体层 — Phase 2.5 数据隔离加固
 *
 * Hibernate @FilterDef 必须全局唯一（一个 entity manager 内只能有一个同名 FilterDef）
 * 所有需要租户隔离的 entity 在此处共享同一个 tenantFilter 定义
 *
 * 用法（在 entity 上）：
 *   @Filter(name = "tenantFilter", condition = "company_id = :tenantId")
 *
 * 启用时机（在 JwtAuthFilter 中）：
 *   session.enableFilter("tenantFilter").setParameter("tenantId", currentUser.getCompanyId())
 */
@org.hibernate.annotations.FilterDef(
    name = "tenantFilter",
    parameters = @org.hibernate.annotations.ParamDef(name = "tenantId", type = Long.class)
)
package com.lingyao.platform.entity;