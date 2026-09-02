package com.lingyao.platform.controller;

import com.lingyao.platform.config.LingyaoSubTaskProperties;
import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.Product;
import com.lingyao.platform.entity.SubTask;
import com.lingyao.platform.entity.SubTaskApproval;
import com.lingyao.platform.repository.ProductRepository;
import com.lingyao.platform.repository.ProductUserGrantRepository;
import com.lingyao.platform.repository.SubTaskApprovalRepository;
import com.lingyao.platform.repository.SubTaskRepository;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.TenantContext;
import com.lingyao.platform.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子任务接入框架 — Bug-23 修复
 *
 * 提供 4 个产品（GEO/HPD/AIDD/POR）统一的接入网关
 *
 * - GET  /api/sub/{code}/info   — 子任务元数据 + 健康状态（需要登录 + 产品可见性）
 * - POST /api/sub/{code}/invoke — 调用子任务（代理或回执 — 本期实现回执，前端可继续跳转 entry_path）
 * - GET  /api/sub/list          — 列出当前公司可见的子任务
 * - GET  /api/sub/pending       — 列出当前公司的待审批（V2.0.19 W6 阶段 2.5 B1）
 * - POST /api/sub/{id}/approve  — 通过审批（V2.0.19 W6 阶段 2.5 B1）
 * - POST /api/sub/{id}/reject   — 拒绝审批（V2.0.19 W6 阶段 2.5 B1）
 */
@RestController
@RequestMapping("/api/sub")
public class SubTaskController {

    @Autowired private ProductRepository productRepo;
    @Autowired private SubTaskRepository subTaskRepo;
    @Autowired private ProductUserGrantRepository productUserGrantRepo;
    @Autowired private SubTaskApprovalRepository subTaskApprovalRepo;   // V2.0.19 W6 阶段 2.5 B1：集中审批
    @Autowired private AuditLogService auditLogService;
    @Autowired private LingyaoSubTaskProperties subTaskProperties;   // V2.0.11 D-1：路由配置外移
    @Autowired private TenantContext tenantContext;                  // V2.0.19 W6 阶段 2.5 B1：公司超管判断

    /**
     * 子任务元信息 + 健康状态
     */
    @GetMapping("/{code}/info")
    public ApiResponse<?> info(@PathVariable String code) {
        Product product = productRepo.findByCode(code.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("未知产品: " + code));
        SubTask task = subTaskRepo.findByProductId(product.getId())
                .orElseThrow(() -> new IllegalArgumentException("产品 " + code + " 尚未注册子任务"));

        // === V2.0.11 D-1：优先读 lingyao.subtask.routes.<code> 配置，fallback 用 sub_task 表 ===
        LingyaoSubTaskProperties.Route route = subTaskProperties.getRoute(code);
        String resolvedBaseUrl = (route != null && route.getBaseUrl() != null && !route.getBaseUrl().isEmpty())
                ? route.getBaseUrl()
                : task.getBaseUrl();
        String resolvedHealthUrl = (route != null && route.getHealthUrl() != null && !route.getHealthUrl().isEmpty())
                ? route.getHealthUrl()
                : task.getHealthUrl();
        String resolvedEntryPath = (route != null && route.getEntryPath() != null && !route.getEntryPath().isEmpty())
                ? route.getEntryPath()
                : task.getEntryPath();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCode", product.getCode());
        data.put("productName", product.getName());
        data.put("productDesc", product.getDescription());
        data.put("taskCode", task.getTaskCode());
        data.put("taskName", task.getTaskName());
        data.put("taskStatus", task.getStatus());
        data.put("entryPath", resolvedEntryPath);
        data.put("apiPrefix", task.getApiPrefix());
        data.put("healthUrl", resolvedHealthUrl);
        data.put("baseUrl", resolvedBaseUrl);
        data.put("ready", task.getStatus() == SubTask.Status.ACTIVE
                        && resolvedBaseUrl != null && !resolvedBaseUrl.isEmpty());
        data.put("source", route != null && route.getBaseUrl() != null ? "config" : "sub_task_table");   // 调试用：标记 baseUrl 来源

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

        // === 权限校验（SSO 直登铁律：主网站鉴权后，没权限 → 直接告知用户，不跳转子网站）===
        if (cu == null) {
            return ApiResponse.fail(401, "请先登录主网站");
        }
        boolean authorized = cu.isPlatformAdmin();  // 平台超管：所有产品直通
        if (!authorized && cu.getCompanyId() != null && cu.getUserId() != null) {
            authorized = productUserGrantRepo.existsByCompanyIdAndProductIdAndUserIdAndStatus(
                    cu.getCompanyId(), product.getId(), cu.getUserId(),
                    com.lingyao.platform.entity.ProductUserGrant.Status.ACTIVE);
        }
        if (!authorized) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("authorized", false);
            err.put("productCode", product.getCode());
            err.put("productName", product.getName());
            err.put("reason", "NO_ACCESS");
            return ApiResponse.fail(403,
                    "您没有「" + product.getName() + "」的访问权限，请联系管理员开通后重试",
                    err);
        }

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

            // === V2.0.11 D-1：优先读 lingyao.subtask.routes.<code>.base-url 配置（环境变量可注入）===
            // Fallback 才用 sub_task.base_url（向后兼容 dev 环境快速回滚）
            LingyaoSubTaskProperties.Route route = subTaskProperties.getRoute(code);
            String url = (route != null && route.getBaseUrl() != null && !route.getBaseUrl().isEmpty())
                    ? route.getBaseUrl()
                    : task.getBaseUrl();

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
            // V2.0.14 PRD-1.4 (选项 C · URL fragment)：token 不再进 URL Query，避免泄漏到 history/Referer/access log
            //   - 子产品前端 sso-callback 页面改为读 window.location.hash 中的 platform_token
            //   - 后续 batch 升级为选项 A (server-side proxy) 时彻底消除 token 进浏览器
            StringBuilder allParams = new StringBuilder(params);
            if (token != null && !token.isEmpty()) {
                allParams.append("#platform_token=").append(java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8));
            }

            data.put("redirectUrl", url + allParams.toString());
            data.put("mode", "DIRECT_REDIRECT");
            data.put("tokenInFragment", true);   // 标记 token 在 URL fragment，子产品前端读 hash
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

    // ════════════════════════════════════════════════════════════════
    // V2.0.19 W6 阶段 2.5 B1 — 审批接口（V2.0.38 起前端已下线入口）
    //
    // 主人 2026-09-02 14:44 终版拍板：全部审批归各子平台自管（责任清晰，
    // 避免多产品下公司超管操作混乱）。主站 pending/approve/reject 保留路由
    // 与 autoGrantOnApprove 联动（历史数据审计兼容 + 未来服务端间调用备用），
    // 但主站前端无任何入口。
    // ════════════════════════════════════════════════════════════════

    /**
     * GET /api/sub/pending — 列出当前公司的待审批（V2.0.19 W6 阶段 2.5 B1）
     *
     * 返回结构（前端字段兼容：
     *   id / taskId | type | requester / applicant | productCode / targetProduct
     *   reason / description | submittedAt / createdAt | status | action
     * ）
     */
    @GetMapping("/pending")
    public ApiResponse<?> pending() {
        CurrentUser cu = CurrentUser.get();
        if (cu == null) return ApiResponse.fail(401, "请先登录主网站");
        // 权限校验：仅公司超管 + 平台超管可看审批
        if (!canApprove(cu, null)) {
            return ApiResponse.fail(403, "仅超管可查看审批列表");
        }

        List<SubTaskApproval> rows;
        if (cu.isPlatformAdmin()) {
            // 平台超管：看所有公司的待审批
            rows = subTaskApprovalRepo.findByStatusOrderByCreatedAtDesc(SubTaskApproval.Status.PENDING);
        } else {
            // 公司超管：仅看本公司
            if (cu.getCompanyId() == null) {
                return ApiResponse.ok(new ArrayList<>());
            }
            rows = subTaskApprovalRepo.findByCompanyIdAndStatusOrderByCreatedAtDesc(
                    cu.getCompanyId(), SubTaskApproval.Status.PENDING);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (SubTaskApproval a : rows) {
            result.add(toApprovalView(a));
        }
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/sub/{id}/approve — 通过审批（V2.0.19 W6 阶段 2.5 B1）
     *
     * Body: { type: "sub_task" }（type 字段保留兼容前端入参，本接口仅按 id 定位）
     */
    @PostMapping("/{id}/approve")
    @Transactional
    public ApiResponse<?> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        CurrentUser cu = CurrentUser.get();
        if (cu == null) return ApiResponse.fail(401, "请先登录主网站");
        if (!canApprove(cu, null)) {
            return ApiResponse.fail(403, "仅超管可审批");
        }

        SubTaskApproval approval = subTaskApprovalRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("审批不存在: id=" + id));

        // 权限二次校验：公司超管只能审批本公司
        if (!cu.isPlatformAdmin() && !approval.getCompanyId().equals(cu.getCompanyId())) {
            return ApiResponse.fail(403, "无权审批其他公司的请求");
        }

        if (approval.getStatus() != SubTaskApproval.Status.PENDING) {
            return ApiResponse.fail(400, "该审批已处理，当前状态: " + approval.getStatus());
        }

        approval.setStatus(SubTaskApproval.Status.APPROVED);
        approval.setApproverId(cu.getUserId());
        approval.setApproverUsername(cu.getUsername());
        approval.setApproveTime(LocalDateTime.now());
        SubTaskApproval saved = subTaskApprovalRepo.save(approval);

        // V2.0.36：PRODUCT_GRANT 类型审批通过 → 自动写 product_user_grant（员工下次进入直接放行）
        autoGrantOnApprove(saved);

        auditLogService.record("SUBTASK_APPROVAL_APPROVE", "APPROVAL",
                String.valueOf(saved.getId()),
                "通过审批 #" + saved.getId() + " type=" + saved.getType()
                        + " requester=" + saved.getRequesterUsername());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", saved.getId());
        data.put("status", saved.getStatus());
        data.put("approverUsername", saved.getApproverUsername());
        data.put("approveTime", saved.getApproveTime());
        return ApiResponse.ok("审批已通过", data);
    }

    /**
     * POST /api/sub/{id}/reject — 拒绝审批（V2.0.19 W6 阶段 2.5 B1）
     *
     * Body: { type: "sub_task", reason: "拒绝理由" }
     */
    @PostMapping("/{id}/reject")
    @Transactional
    public ApiResponse<?> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        CurrentUser cu = CurrentUser.get();
        if (cu == null) return ApiResponse.fail(401, "请先登录主网站");
        if (!canApprove(cu, null)) {
            return ApiResponse.fail(403, "仅超管可审批");
        }

        SubTaskApproval approval = subTaskApprovalRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("审批不存在: id=" + id));

        if (!cu.isPlatformAdmin() && !approval.getCompanyId().equals(cu.getCompanyId())) {
            return ApiResponse.fail(403, "无权审批其他公司的请求");
        }

        if (approval.getStatus() != SubTaskApproval.Status.PENDING) {
            return ApiResponse.fail(400, "该审批已处理，当前状态: " + approval.getStatus());
        }

        String reason = body == null ? null : (String) body.get("reason");

        approval.setStatus(SubTaskApproval.Status.REJECTED);
        approval.setApproverId(cu.getUserId());
        approval.setApproverUsername(cu.getUsername());
        approval.setApproveTime(LocalDateTime.now());
        approval.setRejectReason(reason);
        SubTaskApproval saved = subTaskApprovalRepo.save(approval);

        auditLogService.record("SUBTASK_APPROVAL_REJECT", "APPROVAL",
                String.valueOf(saved.getId()),
                "拒绝审批 #" + saved.getId() + " type=" + saved.getType()
                        + " requester=" + saved.getRequesterUsername()
                        + " reason=" + (reason == null ? "(无)" : reason));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", saved.getId());
        data.put("status", saved.getStatus());
        data.put("approverUsername", saved.getApproverUsername());
        data.put("approveTime", saved.getApproveTime());
        data.put("rejectReason", saved.getRejectReason());
        return ApiResponse.ok("审批已拒绝", data);
    }

    /**
     * 内部：判断当前用户是否可审批
     *
     * - 平台超管：✅ 任意公司
     * - 公司超管：✅ 自己公司（用 cu.getCompanyId()）
     * - 普通用户：❌
     *
     * @param targetCompanyId 目标审批所属公司（null=仅做"是否能审批"粗判，用于 pending 列表）
     */
    private boolean canApprove(CurrentUser cu, Long targetCompanyId) {
        if (cu == null) return false;
        if (cu.isPlatformAdmin()) return true;
        if (cu.getCompanyId() == null) return false;
        if (targetCompanyId != null && !targetCompanyId.equals(cu.getCompanyId())) {
            return false; // 越权公司
        }
        return tenantContext.isTenantAdmin(cu.getUserId(), cu.getCompanyId());
    }

    /**
     * 内部：审批行 → 前端视图
     */
    private Map<String, Object> toApprovalView(SubTaskApproval a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", a.getId());
        row.put("type", a.getType().name());
        // 兼容字段：requester / applicant + productCode / targetProduct + submittedAt / createdAt
        row.put("requester", a.getRequesterUsername());
        row.put("applicant", a.getRequesterUsername());
        if (a.getProductId() != null) {
            Product p = productRepo.findById(a.getProductId()).orElse(null);
            if (p != null) {
                row.put("productCode", p.getCode());
                row.put("targetProduct", p.getCode());
                row.put("productName", p.getName());
            }
        }
        row.put("reason", a.getReason());
        row.put("description", a.getReason());
        row.put("submittedAt", a.getCreatedAt());
        row.put("createdAt", a.getCreatedAt());
        row.put("status", a.getStatus().name());
        return row;
    }

    // ════════════════════════════════════════════════════════════════
    // V2.0.36 — 员工申请产品权限（SSO 双轨制 · 主站侧）
    //
    // 背景（主人 2026-09-02 13:02 拍板）：
    // - 公司配置 SSO 后，员工扫码/点登录直接进子平台；无权限 → 向公司超管申请
    // - 公司超管用「主网站账密」登录子平台处理审批（双轨：员工走 SSO、超管走账密）
    // - 审批通过 → 自动写 product_user_grant → 员工下次进入直接放行
    // ════════════════════════════════════════════════════════════════

    /**
     * POST /api/sub/{code}/apply — 【V2.0.38 已停用】
     *
     * 主人 2026-09-02 14:44 拍板：全部审批（含产品开通申请）归各子平台自管，
     * 主站不做审批、不收申请。本接口保留路由但返回 410 引导，避免子平台/旧前端调用报 404。
     * 员工申请入口 → 各子平台内提交 → 公司超管在子平台内审批。
     */
    @PostMapping("/{code}/apply")
    public ApiResponse<?> apply(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.fail(410, "申请入口已迁移：请在对应子平台内提交开通申请（V2.0.38 审批全面下放子平台）");
    }

    /**
     * GET /api/sub/my-approvals — 【V2.0.38 已停用】返回空列表（前端已无调用方；保留路由防旧缓存页面报错）
     */
    @GetMapping("/my-approvals")
    public ApiResponse<?> myApprovals() {
        return ApiResponse.ok(new ArrayList<>());
    }

    /** V2.0.36：我的申请列表（按申请人倒序，由仓库提供） */

    @Autowired private com.lingyao.platform.repository.CompanyProductRepository companyProductRepo;

    /**
     * 内部（V2.0.36 重载）：审批通过后的自动授权联动
     * PRODUCT_GRANT 类型 → 写 product_user_grant（幂等：REVOKED 复活 / 不存在新建）
     */
    private void autoGrantOnApprove(SubTaskApproval approval) {
        if (approval.getType() != SubTaskApproval.Type.PRODUCT_GRANT) return;
        if (approval.getProductId() == null) return;
        Long companyId = approval.getCompanyId();
        Long productId = approval.getProductId();
        Long userId = approval.getRequesterId();

        com.lingyao.platform.entity.ProductUserGrant g = productUserGrantRepo
                .findByCompanyIdAndProductIdAndUserId(companyId, productId, userId)
                .map(existing -> {
                    existing.setStatus(com.lingyao.platform.entity.ProductUserGrant.Status.ACTIVE);
                    return productUserGrantRepo.save(existing);
                })
                .orElseGet(() -> {
                    com.lingyao.platform.entity.ProductUserGrant ng = new com.lingyao.platform.entity.ProductUserGrant();
                    ng.setCompanyId(companyId);
                    ng.setProductId(productId);
                    ng.setUserId(userId);
                    ng.setGrantedBy(approval.getApproverId() != null ? approval.getApproverId() : 0L);
                    ng.setStatus(com.lingyao.platform.entity.ProductUserGrant.Status.ACTIVE);
                    return productUserGrantRepo.save(ng);
                });
        auditLogService.record("PRODUCT_GRANT_AUTO", "USER", String.valueOf(userId),
                "审批通过自动授权：user=" + approval.getRequesterUsername()
                        + " productId=" + productId + " grantId=" + g.getId());
    }
}

// V2.0.19 W6 阶段 2 注：本类级别前缀为 /api/sub，专门处理「子任务接入」相关接口
//   专属链接生成/验签接口已拆分到 SubProductLinkController（独立 controller，避免路径冲突）
