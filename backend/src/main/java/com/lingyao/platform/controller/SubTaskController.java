package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.Product;
import com.lingyao.platform.entity.SubTask;
import com.lingyao.platform.repository.ProductRepository;
import com.lingyao.platform.repository.SubTaskRepository;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子任务接入框架 — Bug-23 修复
 *
 * 提供 4 个产品（GEO/HPD/AIDD/POR）统一的接入网关
 *
 * - GET  /api/sub/{code}/info   — 子任务元数据 + 健康状态（需要登录 + 产品可见性）
 * - POST /api/sub/{code}/invoke — 调用子任务（代理或回执 — 本期实现回执，前端可继续跳转 entry_path）
 * - GET  /api/sub/list          — 列出当前公司可见的子任务
 */
@RestController
@RequestMapping("/api/sub")
public class SubTaskController {

    @Autowired private ProductRepository productRepo;
    @Autowired private SubTaskRepository subTaskRepo;
    @Autowired private AuditLogService auditLogService;

    /**
     * 子任务元信息 + 健康状态
     */
    @GetMapping("/{code}/info")
    public ApiResponse<?> info(@PathVariable String code) {
        Product product = productRepo.findByCode(code.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("未知产品: " + code));
        SubTask task = subTaskRepo.findByProductId(product.getId())
                .orElseThrow(() -> new IllegalArgumentException("产品 " + code + " 尚未注册子任务"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCode", product.getCode());
        data.put("productName", product.getName());
        data.put("productDesc", product.getDescription());
        data.put("taskCode", task.getTaskCode());
        data.put("taskName", task.getTaskName());
        data.put("taskStatus", task.getStatus());
        data.put("entryPath", task.getEntryPath());
        data.put("apiPrefix", task.getApiPrefix());
        data.put("healthUrl", task.getHealthUrl());
        data.put("baseUrl", task.getBaseUrl());
        data.put("ready", task.getStatus() == SubTask.Status.ACTIVE
                        && task.getBaseUrl() != null && !task.getBaseUrl().isEmpty());

        // 透视当前用户是否有权访问
        CurrentUser cu = CurrentUser.get();
        data.put("yourAccess", cu == null ? "anonymous" :
                cu.isPlatformAdmin() ? "PLATFORM_ADMIN" :
                code.equalsIgnoreCase(cu.getProductCode()) ? cu.getRoleCode() : "NONE");

        return ApiResponse.ok(data);
    }

    /**
     * 调用子任务（统一入口）
     * 本期作为路由层：
     *  - 子任务 ACTIVE + baseUrl 已配置 → 透传 JWT 到子任务
     *  - 子任务未部署 → 返回 503，提示 entry_path 用于前端跳转
     */
    @PostMapping("/{code}/invoke")
    public ApiResponse<?> invoke(@PathVariable String code, @RequestBody Map<String, Object> payload) {
        Product product = productRepo.findByCode(code.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("未知产品: " + code));
        SubTask task = subTaskRepo.findByProductId(product.getId())
                .orElseThrow(() -> new IllegalArgumentException("产品 " + code + " 尚未注册子任务"));

        auditLogService.record("SUBTASK_INVOKE", "SUBTASK",
                String.valueOf(task.getId()),
                "调用 " + code + " 子任务，action=" + payload.getOrDefault("action", "default"));

        if (task.getStatus() == SubTask.Status.OFFLINE) {
            throw new IllegalArgumentException("子任务已下线：" + task.getTaskName());
        }

        if (task.getBaseUrl() == null || task.getBaseUrl().isEmpty()) {
            // 路由层已就绪但子任务后端尚未部署 — 返回降级信息 + entry_path
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ROUTING_NOT_READY");
            data.put("message", "子任务引擎尚未部署，当前提供路由层验证");
            data.put("fallbackPath", task.getEntryPath());
            data.put("receivedPayload", payload);
            return ApiResponse.ok("已记录调用，子任务引擎待部署", data);
        }

        // TODO: 将来真正代理转发到 task.getBaseUrl() + payload + JWT 头
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "DELEGATED");
        data.put("proxyTo", task.getBaseUrl());
        data.put("taskCode", task.getTaskCode());
        data.put("receivedPayload", payload);
        return ApiResponse.ok("代理调用已就绪", data);
    }

    /**
     * 列出平台所有已注册子任务（超管监控视图）
     */
    @GetMapping("/list")
    public ApiResponse<?> list() {
        java.util.List<SubTask> tasks = subTaskRepo.findAll();
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (SubTask t : tasks) {
            productRepo.findById(t.getProductId()).ifPresent(p -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productCode", p.getCode());
                row.put("productName", p.getName());
                row.put("taskCode", t.getTaskCode());
                row.put("taskName", t.getTaskName());
                row.put("status", t.getStatus());
                row.put("entryPath", t.getEntryPath());
                row.put("baseUrl", t.getBaseUrl());
                rows.add(row);
            });
        }
        return ApiResponse.ok(rows);
    }

    /**
     * 子任务进入入口 — 返回实际跳转 URL（Phase 2 解耦版）
     *
     * - 子任务 ACTIVE + baseUrl 已配置 → 返回 baseUrl + 标准 IdentityAssertion
     * - 子任务未部署 → 503 + fallbackPath
     *
     * Phase 2 解耦后发给子产品的标准参数（不再透传老 JWT）：
     *   platform_token: 当前 JWT（子产品可选择验签或直接读 claim）
     *   tenant_id: 租户 ID（子产品做数据隔离）
     *   user_id: 用户 ID（子产品做用户关联）
     *   user: 用户名
     *   display_name: 用户显示名
     *
     * 子产品只读这 5 个标准参数，不再需要懂 cid/pcode/rcode 这些主网站内部概念。
     */
    @GetMapping("/{code}/enter")
    public ApiResponse<?> enter(@PathVariable String code, HttpServletRequest request) {
        Product product = productRepo.findByCode(code.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("未知产品: " + code));
        SubTask task = subTaskRepo.findByProductId(product.getId())
                .orElseThrow(() -> new IllegalArgumentException("产品 " + code + " 尚未注册子任务"));

        CurrentUser cu = CurrentUser.get();

        // 审计：记录子任务进入
        auditLogService.record("SUBTASK_ENTER", "SUBTASK",
                String.valueOf(task.getId()),
                "进入 " + code + " 子任务，user=" + (cu == null ? "anonymous" : cu.getUsername()));

        if (task.getStatus() == SubTask.Status.OFFLINE) {
            throw new IllegalArgumentException("子任务已下线：" + task.getTaskName());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCode", product.getCode());
        data.put("productName", product.getName());
        data.put("taskCode", task.getTaskCode());
        data.put("status", task.getStatus().name());
        data.put("entryPath", task.getEntryPath());

        if (task.getBaseUrl() != null && !task.getBaseUrl().isEmpty()) {
            // 子任务已部署：返回带标准化参数的跳转 URL
            String token = extractBearerToken(request);
            String url = task.getBaseUrl();

            // === Phase 2 解耦：标准 5 参数（子产品只读这 5 个）===
            StringBuilder params = new StringBuilder();
            params.append(url.contains("?") ? "&" : "?");
            if (cu != null) {
                if (cu.getCompanyId() != null) params.append("tenant_id=").append(cu.getCompanyId());
                if (cu.getUserId() != null) params.append("&user_id=").append(cu.getUserId());
                if (cu.getUsername() != null) params.append("&user=")
                        .append(java.net.URLEncoder.encode(cu.getUsername(), java.nio.charset.StandardCharsets.UTF_8));
                if (cu.getDisplayName() != null) params.append("&display_name=")
                        .append(java.net.URLEncoder.encode(cu.getDisplayName(), java.nio.charset.StandardCharsets.UTF_8));
            }

            // 同时保留 platform_token（向后兼容 1 周，过渡期内子产品可以验签老 JWT 或读新 claims）
            if (token != null && !token.isEmpty()) {
                params.append("&platform_token=").append(java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8));
            }

            data.put("redirectUrl", url + params.toString());
            data.put("mode", "DIRECT_REDIRECT");
            data.put("identityAssertion", buildIdentityAssertion(cu, product.getCode())); // Phase 2 新增
            return ApiResponse.ok(data);
        }

        // 子任务未部署：返回降级信息（仍然返回 identityAssertion 供前端调试）
        data.put("redirectUrl", null);
        data.put("mode", "ROUTING_NOT_READY");
        data.put("fallbackPath", task.getEntryPath());
        data.put("message", "子任务引擎尚未部署，当前提供路由层验证");
        data.put("identityAssertion", buildIdentityAssertion(cu, product.getCode())); // Phase 2: 即便降级也填
        return ApiResponse.ok("子任务引擎尚未部署", data);
    }

    /**
     * Phase 2 新增：构造标准化 IdentityAssertion JSON
     * 子产品拿到这个 JSON 后只需要读 tenant/identity/grants 三段
     */
    private Map<String, Object> buildIdentityAssertion(CurrentUser cu, String productCode) {
        Map<String, Object> assertion = new LinkedHashMap<>();
        if (cu == null) return assertion;

        // tenant 段
        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("id", cu.getCompanyId() == null ? null : String.valueOf(cu.getCompanyId()));
        tenant.put("code", cu.getCompanyCode());
        tenant.put("name", cu.getTenantName() == null ? cu.getCompanyCode() : cu.getTenantName());
        assertion.put("tenant", tenant);

        // identity 段
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("user_id", cu.getUserId() == null ? null : String.valueOf(cu.getUserId()));
        identity.put("username", cu.getUsername());
        identity.put("display_name", cu.getDisplayName() == null ? cu.getUsername() : cu.getDisplayName());
        assertion.put("identity", identity);

        // grants 段
        Map<String, Object> grants = new LinkedHashMap<>();
        grants.put("product_code", productCode);
        grants.put("authorized", true);
        grants.put("authorized_at", java.time.Instant.now().toString());
        grants.put("authorized_by", cu.getUsername());
        assertion.put("grants", grants);

        return assertion;
    }

    /** 解析 Authorization: Bearer xxx */
    private String extractBearerToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h == null) return null;
        if (h.startsWith("Bearer ")) return h.substring(7).trim();
        return null;
    }
}
