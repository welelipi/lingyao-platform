package com.lingyao.platform.controller;

import com.lingyao.platform.config.LingyaoSubTaskProperties;
import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.Product;
import com.lingyao.platform.entity.ProductUserGrant;
import com.lingyao.platform.entity.SubTask;
import com.lingyao.platform.repository.ProductRepository;
import com.lingyao.platform.repository.ProductUserGrantRepository;
import com.lingyao.platform.repository.SubTaskRepository;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtUtil;
import com.lingyao.platform.security.TenantContext;
import com.lingyao.platform.service.AuditLogService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子产品专属链接 Controller（V2.0.19 W6 阶段 2 新增）
 *
 * 为什么独立一个 Controller？
 * - SubTaskController 类级别 @RequestMapping 指向 api/sub 前缀，方法再用 PostMapping admin
 *   会拼出 api/sub admin 错误路径
 * - 期望路径：api/admin/sub-product/CODE/generate-link 与 api/sub-product/CODE/verify-link
 * - 所以拆出来单独注册
 *
 * 接口清单：
 * - POST api/admin/sub-product/CODE/generate-link — 公司超管/平台超管 生成 5 分钟过期专属链接
 * - POST api/sub-product/CODE/verify-link       — 子平台 验签（白名单，无需主平台登录）
 *
 * 鉴权策略：
 * - generate-link：手动校验（PreAuthorize 需要全局方法安全开启，主平台未启用）
 * - verify-link：白名单（SecurityConfig 已加 sub-product 任意 code 的 verify-link 放行）
 *
 * @since V2.0.19 W6
 */
@RestController
public class SubProductLinkController {

    @Autowired private ProductRepository productRepo;
    @Autowired private SubTaskRepository subTaskRepo;
    @Autowired private ProductUserGrantRepository productUserGrantRepo;
    @Autowired private AuditLogService auditLogService;
    @Autowired private LingyaoSubTaskProperties subTaskProperties;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private TenantContext tenantContext;

    /**
     * 专属链接生成（公司超管/平台超管 调用）
     *
     * 路径：POST /api/admin/sub-product/{code}/generate-link
     * 鉴权：需要登录 + 平台超管 OR 公司超管（手动校验）
     *
     * 输出（成功）：
     * {
     *   "success": true,
     *   "message": "专属链接生成成功，5 分钟内有效",
     *   "data": {
     *     "link": "http://localhost:5180/?token=<5min-jwt>&product=GEOM&issued_by=admin",
     *     "expires_at": "2026-09-01T15:58:30Z",
     *     "token_ttl_seconds": 300,
     *     "product_code": "GEOM",
     *     "product_name": "GEO 棱镜",
     *     "company_id": 1,
     *     "company_name": "B 药企",
     *     "issued_by": "admin",
     *     "generated_at": "2026-09-01T15:53:30Z"
     *   }
     * }
     *
     * 后端校验：
     * - 产品存在 + sub_task 已注册
     * - 子任务 ACTIVE + baseUrl 已配置
     * - 当前用户是平台超管 OR 公司超管
     * - 公司已开通该产品（ProductUserGrant ACTIVE）
     *
     * 写 audit：COMPANY_SUB_PRODUCT_LINK_GENERATED
     */
    @PostMapping("/api/admin/sub-product/{code}/generate-link")
    public ApiResponse<?> generateLink(@PathVariable String code) {
        CurrentUser cu = CurrentUser.get();
        if (cu == null) return ApiResponse.fail(401, "请先登录");

        Product product = productRepo.findByCode(code.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("未知产品: " + code));
        SubTask task = subTaskRepo.findByProductId(product.getId())
                .orElseThrow(() -> new IllegalArgumentException("产品 " + code + " 尚未注册子任务"));

        // 鉴权：平台超管 OR 公司超管
        if (!cu.isPlatformAdmin()) {
            if (cu.getCompanyId() == null) {
                return ApiResponse.fail(403, "您不是平台超管，且未关联任何公司，无法生成专属链接");
            }
            if (!tenantContext.isTenantAdmin(cu.getUserId(), cu.getCompanyId())) {
                return ApiResponse.fail(403, "您不是该公司超管，无权生成专属链接");
            }
            if (!productUserGrantRepo.existsByCompanyIdAndProductIdAndUserIdAndStatus(
                    cu.getCompanyId(), product.getId(), cu.getUserId(),
                    ProductUserGrant.Status.ACTIVE)) {
                return ApiResponse.fail(403, "您的公司未开通「" + product.getName() + "」");
            }
        }

        // 子任务必须 ACTIVE + baseUrl 已配置
        if (task.getStatus() == SubTask.Status.OFFLINE) {
            return ApiResponse.fail(503, "子任务已下线: " + task.getTaskName());
        }
        LingyaoSubTaskProperties.Route route = subTaskProperties.getRoute(code);
        String resolvedBaseUrl = (route != null && route.getBaseUrl() != null && !route.getBaseUrl().isEmpty())
                ? route.getBaseUrl()
                : task.getBaseUrl();
        if (resolvedBaseUrl == null || resolvedBaseUrl.isEmpty()) {
            return ApiResponse.fail(503, "子任务尚未部署，无法生成专属链接");
        }

        // 生成 5 分钟过期 JWT
        Long companyId = cu.getCompanyId();
        String companyCode = cu.getCompanyCode();
        String companyName = cu.getTenantName();
        String roleCode = "COMPANY_ADMIN";

        String token = jwtUtil.generateShortLinkToken(
                companyId, companyCode, product.getCode(), roleCode,
                cu.getUserId(), cu.getUsername());

        // 拼装 URL — token 在 query 里（子平台前端读 query 后立即调 verify-link，然后丢弃 query 防泄漏）
        // 注意：resolvedBaseUrl 可能含 fragment（如 Vue hash 路由 http://host:5180/#/auth/lingyao/sso-callback）
        //       需要把 query 拼在 fragment 前面，避免 token 被吃进 fragment
        int hashIdx = resolvedBaseUrl.indexOf('#');
        String baseWithoutFragment = hashIdx >= 0 ? resolvedBaseUrl.substring(0, hashIdx) : resolvedBaseUrl;
        String fragment = hashIdx >= 0 ? resolvedBaseUrl.substring(hashIdx) : "";

        String url = baseWithoutFragment
                + (baseWithoutFragment.contains("?") ? "&" : "?")
                + "token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)
                + "&product=" + product.getCode()
                + "&issued_by=" + java.net.URLEncoder.encode(cu.getUsername() == null ? "" : cu.getUsername(),
                        java.nio.charset.StandardCharsets.UTF_8)
                + fragment;   // 把 fragment 重新拼到末尾

        // 审计
        auditLogService.record("COMPANY_SUB_PRODUCT_LINK_GENERATED", "SUBTASK",
                String.valueOf(task.getId()),
                "公司超管 " + cu.getUsername() + " 生成 " + code + " 专属链接，过期 5 分钟");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("link", url);
        data.put("expires_at", Instant.now().plusSeconds(jwtUtil.getShortLinkTtlSeconds()).toString());
        data.put("token_ttl_seconds", jwtUtil.getShortLinkTtlSeconds());
        data.put("product_code", product.getCode());
        data.put("product_name", product.getName());
        data.put("company_id", companyId);
        data.put("company_name", companyName == null ? companyCode : companyName);
        data.put("issued_by", cu.getUsername());
        data.put("generated_at", Instant.now().toString());

        return ApiResponse.ok("专属链接生成成功，5 分钟内有效", data);
    }

    /**
     * 专属链接验签（子平台调用，白名单）
     *
     * 路径：POST /api/sub-product/{code}/verify-link
     * 鉴权：白名单（SecurityConfig 已放行，员工可能还没主平台账号）
     *
     * 输入：{ "token": "<5min-jwt>" }
     * 输出（成功）：
     * {
     *   "success": true,
     *   "data": {
     *     "valid": true,
     *     "company_id": 1,
     *     "company_code": "B_DRUG",
     *     "product_code": "GEOM",
     *     "role_code": "COMPANY_ADMIN",
     *     "issued_by_user_id": 1,
     *     "issued_by_username": "admin",
     *     "issued_at": "2026-09-01T15:53:30Z",
     *     "expires_at": "2026-09-01T15:58:30Z",
     *     "nonce": "uuid-xxx"
     *   }
     * }
     *
     * 失败：
     * - 400 token 为空
     * - 401 LINK_EXPIRED（过期）
     * - 401 INVALID_TOKEN（签名错 / audience 不匹配 / link_type 不匹配）
     * - 400 PRODUCT_MISMATCH（URL 产品 code 与 token 内 pcode 不一致）
     *
     * 写 audit：COMPANY_SUB_PRODUCT_LINK_VERIFIED
     */
    @PostMapping("/api/sub-product/{code}/verify-link")
    public ApiResponse<?> verifyLink(@PathVariable String code, @RequestBody Map<String, Object> body) {
        String token = (String) body.get("token");
        if (token == null || token.isEmpty()) {
            return ApiResponse.fail(400, "token 不能为空");
        }

        Claims claims;
        try {
            claims = jwtUtil.parseShortLinkToken(token);
        } catch (ExpiredJwtException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("reason", "LINK_EXPIRED");
            return ApiResponse.fail(401, "专属链接已过期，请联系管理员重新生成", err);
        } catch (JwtException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("reason", "INVALID_TOKEN");
            return ApiResponse.fail(401, "专属链接无效: " + e.getMessage(), err);
        }

        // 校验 product_code 是否匹配（URL 路径 {code} vs token 内 pcode）
        String tokenProductCode = (String) claims.get("pcode");
        if (tokenProductCode == null || !tokenProductCode.equalsIgnoreCase(code)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("reason", "PRODUCT_MISMATCH");
            err.put("url_product", code);
            err.put("token_product", tokenProductCode);
            return ApiResponse.fail(400, "专属链接产品不匹配", err);
        }

        // 审计
        auditLogService.record("COMPANY_SUB_PRODUCT_LINK_VERIFIED", "SUBTASK",
                String.valueOf(claims.get("cid")) + ":" + code,
                "验签专属链接: company=" + claims.get("cid") + " product=" + code +
                        " role=" + claims.get("rcode"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", true);
        data.put("company_id", claims.get("cid"));
        data.put("company_code", claims.get("ccode"));
        data.put("product_code", claims.get("pcode"));
        data.put("role_code", claims.get("rcode"));
        data.put("issued_by_user_id", claims.get("uid"));
        data.put("issued_by_username", claims.get("username"));
        data.put("issued_at", claims.get("generated_at"));
        data.put("expires_at", claims.getExpiration().toInstant().toString());
        data.put("nonce", claims.get("nonce"));

        return ApiResponse.ok("专属链接验签成功", data);
    }
}