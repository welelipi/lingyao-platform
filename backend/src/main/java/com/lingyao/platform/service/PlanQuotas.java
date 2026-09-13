package com.lingyao.platform.service;

import java.util.HashMap;
import java.util.Map;

/**
 * V2.0.77.22 D-4 trial 套餐支持 · 主站侧套餐配额常量
 *
 * 契约：/tmp/MainSite_plan_contract_V3.md
 * 镜像 GEOM 侧 app/core/plan_quotas.py（契约 V3 §2 主站/GEO 镜像表）
 *
 * 主站 backend 不直接做配额拦截（拦截在 GEOM 侧，主站只下发 plan 到 JWT claims）。
 * 这里作为唯一真源，供：
 * - admin.html 套餐管理 UI 渲染（"还可建 X 个分类"等提示）
 * - TrialActivationService 开通流水线写入 geom_plan=trial + trial_expires_at
 * - LicenseExpirationScheduler 每日扫 trial_expires_at 到期流转
 * - 公司超管后台展示套餐剩余配额（与 GEOM /api/stats/usage 端点对齐）
 */
public final class PlanQuotas {

    private PlanQuotas() {}

    /** 单档配额定义 */
    public static class Quota {
        public final int maxCategories;
        public final int maxBrandsPerCategory;
        public final int maxUsers;             // 用户席位（trial=1, basic=10, pro=18, flagship=不限）
        public final int dataRetentionDays;    // 数据回溯窗口（trial=7, basic=90, pro=365, flagship=-1=不限）
        public final int contractTermMonths;   // 合同期（trial=1 月（30天试用）, basic=12, pro=12, flagship=12）
        public final int annualFeeYuan;        // 年费人民币元（trial=0, basic=200000, pro=480000, flagship=980000）

        public Quota(int maxCategories, int maxBrandsPerCategory, int maxUsers,
                     int dataRetentionDays, int contractTermMonths, int annualFeeYuan) {
            this.maxCategories = maxCategories;
            this.maxBrandsPerCategory = maxBrandsPerCategory;
            this.maxUsers = maxUsers;
            this.dataRetentionDays = dataRetentionDays;
            this.contractTermMonths = contractTermMonths;
            this.annualFeeYuan = annualFeeYuan;
        }
    }

    /**
     * V3 套餐表（plan_contract_V3.md §1）
     * D-4 主人 10:32 拍板：①trial 1 席 / ②7 天回溯 / ③flagship 不限 + retention 清理
     */
    public static final Map<String, Quota> PLAN_QUOTAS = new HashMap<>();
    static {
        PLAN_QUOTAS.put("trial",    new Quota(1,  3,  1,  7,   1, 0));
        PLAN_QUOTAS.put("basic",    new Quota(3,  10, 10, 90,  12, 200000));
        PLAN_QUOTAS.put("pro",      new Quota(5,  20, 18, 365, 12, 480000));
        PLAN_QUOTAS.put("flagship", new Quota(10, 50, -1, -1,  12, 980000));  // -1 = 不限
    }

    /** 套餐中文名（与 portal.html / admin.html 渲染对齐） */
    public static final Map<String, String> PLAN_ZH = new HashMap<>();
    static {
        PLAN_ZH.put("trial",    "试用");
        PLAN_ZH.put("basic",    "基础");
        PLAN_ZH.put("pro",      "专业");
        PLAN_ZH.put("flagship", "旗舰");
    }

    /** 套餐色（与 portal.html CSS 4 色对齐） */
    public static final Map<String, String> PLAN_COLOR = new HashMap<>();
    static {
        PLAN_COLOR.put("trial",    "#4A6B3A");  // 墨绿
        PLAN_COLOR.put("basic",    "#5A5F6B");  // 灰
        PLAN_COLOR.put("pro",      "#3D5A80");  // 蓝
        PLAN_COLOR.put("flagship", "#8B6914");  // 金
    }

    public static Quota get(String plan) {
        if (plan == null || plan.isBlank()) return PLAN_QUOTAS.get("basic");  // R1 缺省兜底
        return PLAN_QUOTAS.getOrDefault(plan.toLowerCase(), PLAN_QUOTAS.get("basic"));
    }

    public static String getZh(String plan) {
        return PLAN_ZH.getOrDefault(plan == null ? "basic" : plan.toLowerCase(), plan);
    }

    public static String getColor(String plan) {
        return PLAN_COLOR.getOrDefault(plan == null ? "basic" : plan.toLowerCase(), "#5A5F6B");
    }

    /** 是否 trial 公司 */
    public static boolean isTrial(String plan) {
        return "trial".equalsIgnoreCase(plan);
    }

    /** 校验 plan 枚举 */
    public static boolean isValidPlan(String plan) {
        return plan != null && PLAN_QUOTAS.containsKey(plan.toLowerCase());
    }

    /** retention 清理：返回超过 N 天的数据是否要归档（flagship 全量自动归档保留 2 年） */
    public static boolean needsRetention(String plan) {
        Quota q = get(plan);
        return q.dataRetentionDays == -1;  // flagship 不限 → 需要 retention 防库爆炸
    }
}
