package com.lingyao.platform.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户（从 JWT 解析）— Phase 2 解耦版
 *
 * 主网站三层权限信息：
 * - Layer 1：companyId / companyCode（租户）
 * - Layer 2：userId / username（用户）
 * - 平台角色：platformAdmin
 *
 * 产品内角色（pcode/rcode）已从主网站 CurrentUser 中解耦：
 * - 子产品自己维护产品内角色（OEG 的 role 表、HPD 的 role 表等）
 * - 主网站只通过 grants.product_code 告诉子产品"主网站授权了哪个产品"
 * - 主网站不再告诉子产品"你在这个产品里是什么角色"
 *
 * 老字段 productCode/roleCode 保留为 @Deprecated 方法（1 周过渡期，2026-08-19 后删除）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUser {
    private Long userId;
    private String username;
    private Long companyId;
    private String companyCode;
    private boolean platformAdmin;

    // === Phase 2 新增：标准化断言的便捷读取 ===
    /** 租户显示名（从 tenant.name 读取） */
    private transient String tenantName;
    /** 用户显示名（从 identity.display_name 读取） */
    private transient String displayName;

    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    public static void set(CurrentUser user) {
        CURRENT.set(user);
    }

    public static CurrentUser get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    // === 向后兼容方法（1 周后删除，2026-08-19） ===

    /**
     * @deprecated Phase 2 起，主网站不再维护 productCode。
     *             改为读 grants.product_code（主网站只断言"授权了哪个产品"）。
     *             子产品自身的角色请在子产品自己的 role 表里查。
     */
    @Deprecated
    public String getProductCode() {
        return null; // Phase 2 起主网站不再持有产品内角色
    }

    /**
     * @deprecated Phase 2 起，主网站不再维护 roleCode。
     *             子产品自己定义角色（如 OEG 的 viewer/editor/admin）。
     */
    @Deprecated
    public String getRoleCode() {
        return null;
    }
}