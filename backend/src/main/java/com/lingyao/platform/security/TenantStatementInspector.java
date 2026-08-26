package com.lingyao.platform.security;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 租户隔离 SQL 拦截器 — Phase 2.5 数据隔离加固（最终方案）
 *
 * 在 Hibernate 生成 SQL 后、发送到 JDBC 之前拦截，强制注入 WHERE company_id = ? 条件
 *
 * 为什么用 StatementInspector 而非 @Filter：
 * - @Filter 是 Session 级，Session 生命周期难控制
 * - StatementInspector 在 SQL 生成时拦截，不依赖 Session
 * - 更可靠、更可控
 *
 * 白名单（不加 tenant_id 条件）：
 * - sys_user：用户跨租户共享
 * - company：公司表本身是租户定义
 * - product：产品目录跨租户共享
 * - sub_task：子任务配置跨租户共享
 * - notification_channel：推送通道跨租户共享
 * - product_role：产品内角色定义跨租户共享
 * - registration：线索跨租户（平台超管统一管理）
 * - company_audit_log：用 @Filter 即可（暂保留双保险，但实际由本拦截器兜底）
 *
 * 豁免条件：
 * - 当前用户为 null（公开端点）→ 不注入
 * - 当前用户为平台超管 → 不注入（看全局）
 * - SQL 类型不在白名单 → 不注入
 */
@Component
public class TenantStatementInspector implements StatementInspector {

    private static final Logger log = LoggerFactory.getLogger(TenantStatementInspector.class);

    /**
     * 需要租户隔离的表（HQL 别名 → 表名）
     * 注意：Hibernate 生成的 SQL 用的是表别名后的限定名（如 company_audit_log cal1_0）
     */
    private static final Set<String> TENANT_TABLES = Set.of(
            "company_user",
            "company_product",
            "product_user_grant",
            "product_user_role",
            "invitation",
            "company_audit_log"
    );

    @Override
    public String inspect(String sql) {
        try {
            CurrentUser cu = CurrentUser.get();
            if (cu == null || cu.getCompanyId() == null || cu.isPlatformAdmin()) {
                return sql; // 公开端点或平台超管：不注入
            }

            String lowerSql = sql.toLowerCase();
            boolean isTenantQuery = TENANT_TABLES.stream().anyMatch(lowerSql::contains);
            boolean isMutation = lowerSql.startsWith("select") || lowerSql.startsWith("update") || lowerSql.startsWith("delete");
            if (!isTenantQuery || !isMutation) {
                return sql;
            }

            return injectTenantCondition(sql, cu.getCompanyId());
        } catch (Exception e) {
            // 拦截器绝不能破坏正常 SQL
            log.warn("[Phase2.5] Inspect failed: {}", e.getMessage());
            return sql;
        }
    }

    /**
     * 给 SQL 注入 WHERE company_id = ? 条件
     * 简单策略：检测 WHERE 子句，没有则添加 WHERE，有则添加 AND
     *
     * 修复：之前判断 "是否已有 company_id" 太宽泛（SELECT 子句中的列名也算）
     * 现在改为：先提取 WHERE ... GROUP/ORDER/HAVING/LIMIT 子句，再判断是否含 company_id
     */
    private String injectTenantCondition(String sql, Long tenantId) {
        String lowerSql = sql.toLowerCase();
        String condition = "company_id = " + tenantId;

        // 找到 WHERE 位置和终止位置
        int whereIdx = lowerSql.indexOf(" where ");
        int groupByIdx = lowerSql.indexOf(" group by ");
        int orderByIdx = lowerSql.indexOf(" order by ");
        int havingIdx = lowerSql.indexOf(" having ");
        int limitIdx = lowerSql.indexOf(" fetch first ");

        // 找最靠前的终止位置（WHERE 之后）
        int endIdx = sql.length();
        for (int idx : new int[]{groupByIdx, orderByIdx, havingIdx, limitIdx}) {
            if (idx > 0 && idx < endIdx) endIdx = idx;
        }

        // 检查 WHERE 子句是否已经包含 company_id（避免重复）
        if (whereIdx > 0 && whereIdx < endIdx) {
            String whereClause = lowerSql.substring(whereIdx + 7, endIdx);
            if (whereClause.contains("company_id")) {
                return sql; // 已有过滤，跳过
            }
            // 有 WHERE 但没 company_id：在 WHERE 后插入 AND
            String prefix = sql.substring(0, whereIdx + 7);
            String suffix = sql.substring(whereIdx + 7);
            return prefix + condition + " AND " + suffix;
        } else {
            // 无 WHERE：在终止位置前加 WHERE
            return sql.substring(0, endIdx) + " WHERE " + condition + sql.substring(endIdx);
        }
    }
}