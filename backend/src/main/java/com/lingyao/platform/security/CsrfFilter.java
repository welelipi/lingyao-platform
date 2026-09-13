package com.lingyao.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingyao.platform.entity.AuthAuditEvent;
import com.lingyao.platform.service.AuthAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CsrfFilter — V2.0.25 P2.3 阶段 C
 *
 * CSRF 防护（Double-Submit Cookie 模式）
 *
 * ──── 工作原理 ────
 *
 * 1) 客户端任何请求到达 → 服务端检查 XSRF-TOKEN cookie：
 *    - 缺失 → 生成新 token 写入响应 cookie（Set-Cookie: XSRF-TOKEN=xxx）
 *    - 存在 → 透传，不重写
 *
 * 2) 状态变更请求（POST/PUT/DELETE/PATCH）必须满足：
 *    - XSRF-TOKEN cookie 非空
 *    - X-XSRF-TOKEN header 非空
 *    - 两值完全相等（timing-safe 比较）
 *    否则 → 403 + CSRF 错误消息
 *
 * ──── 豁免路径（与 SecurityConfig permitAll 对齐）────
 *
 * 完全匹配：
 *   /api/auth/login                       — 登录（用户尚未认证）
 *   /api/auth/logout                      — 登出（幂等）
 *   /api/auth/refresh                     — 刷新 token（用 refresh_token 鉴权）
 *   /api/auth/forgot-password             — 忘记密码（无认证状态）
 *   /api/auth/reset-password              — 重置密码（用 token 鉴权）
 *   /api/auth/register                    — 注册
 *   /api/registrations                    — 公开报名
 *   /api/registrations/stats              — 公开统计
 *   /api/feedback                         — 公开反馈
 *   /api/health                           — 健康检查
 *   /api/version                          — 版本信息
 *   /api/_diag/version                    — 诊断端点
 *
 * 前缀匹配：
 *   /api/public/                          — 公开资源
 *   /api/invitations/                     — 邀请相关
 *   /api/sub-product/                     — 子产品回调（verify-link / sso-callback）
 *   /api/_diag/                           — 诊断端点
 *   /h2-console/                          — H2 console（dev only）
 *
 * ──── Filter 顺序 ────
 *
 * SecurityHeadersFilter (HIGHEST + 10)
 *   ↓
 * CsrfFilter            (HIGHEST + 15)   ← 在此
 *   ↓
 * RateLimitFilter       (HIGHEST + 20)
 *   ↓
 * JwtAuthFilter         (UsernamePasswordAuthenticationFilter 前)
 *
 * 把 CSRF 放在 RateLimit 之前：CSRF-rejected 请求不该消耗限流预算。
 *
 * ──── 安全说明 ────
 *
 * - Token 长度 32B (256 bits) → 暴力破解不可行
 * - HttpOnly=false 是有意的：JS 必须读 cookie 才能放进 header
 *   （JWT 在 localStorage 也是 JS 可读的，所以这个攻击面已经存在）
 * - SameSite=Lax 由 cookie 属性提供 → 浏览器自动阻止跨站 POST
 * - timing-safe equals：用 MessageDigest.isEqual 防止时序攻击
 *
 * 参考：
 * - OWASP CSRF Prevention Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html
 * - Double-Submit Cookie pattern: https://en.wikipedia.org/wiki/Cross-site_request_forgery#Double-submit_cookie
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class CsrfFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CsrfFilter.class);

    @Autowired
    private CsrfTokenService csrfTokenService;

    // V2.0.26 P2.4：CSRF 拒绝时记录安全审计事件
    @Autowired
    private AuthAuditService authAuditService;

    // V2.0.71 PRD-1.3：统一 IP 口径
    @Autowired
    private ClientIpResolver clientIpResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 安全方法（不修改状态，不需要 CSRF 校验） */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** 完全匹配的豁免路径 */
    private static final Set<String> EXEMPT_EXACT = Set.of(
            "/api/auth/login",
            "/api/auth/logout",
            "/api/auth/refresh",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/register",
            "/api/auth/device-otp/send",  // V2.0.69 D：新设备邮箱码发送（登录前置，凭用户名+邮箱在库校验）
            "/api/registrations",
            "/api/registrations/stats",
            "/api/feedback",
            "/api/health",
            "/api/version",
            "/api/_diag/version",
            "/api/trial/register"   // V2.0.78.0：trial 公开注册（① 纯公开 · D-5 拍板）
    );

    /** 前缀匹配的豁免路径 */
    private static final List<String> EXEMPT_PREFIXES = Arrays.asList(
            "/api/public/",
            "/api/invitations/",
            "/api/sub-product/",
            "/api/_diag/",
            "/api/internal/",  // V2.0.39：服务间接口（子平台 → 主站 grants sync），无浏览器 cookie，鉴权靠 service JWT
            "/api/auth/2fa/",   // V2.0.26 P2.4：2FA 管理端点（需 JWT 认证，CSRF 攻击面已由 JWT 防住）
            "/h2-console/"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        String method = req.getMethod();
        String path = req.getRequestURI();

        // 去掉 context path（凌瑶未配 context-path，但保险起见处理一下）
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }

        // ═══ 1) 安全方法：仅种 cookie，不做校验 ═══
        if (isSafeMethod(method)) {
            // OPTIONS（CORS 预检）不种 cookie —— 浏览器不处理预检响应的 Set-Cookie，
            // 而且种了也没用，徒增响应大小
            if (!"OPTIONS".equalsIgnoreCase(method)) {
                csrfTokenService.ensureTokenCookie(req, resp);
            }
            chain.doFilter(req, resp);
            return;
        }

        // ═══ 2) 豁免路径：不校验，但仍然种 cookie（让前端能用）═══
        if (isExempt(path)) {
            csrfTokenService.ensureTokenCookie(req, resp);
            chain.doFilter(req, resp);
            return;
        }

        // ═══ 3) 状态变更接口：校验 cookie + header ═══
        String cookieToken = csrfTokenService.getTokenFromCookie(req);
        String headerToken = req.getHeader(CsrfTokenService.HEADER_NAME);

        if (cookieToken == null || cookieToken.isBlank()) {
            log.warn("🚫 CSRF 拦截：缺少 XSRF-TOKEN cookie ({} {})", method, path);
            recordCsrfReject(req, "CSRF_COOKIE_MISSING");
            writeCsrfError(resp, "CSRF token cookie 缺失，请先访问任意 GET 接口获取");
            return;
        }

        if (headerToken == null || headerToken.isBlank()) {
            log.warn("🚫 CSRF 拦截：缺少 X-XSRF-TOKEN header ({} {})", method, path);
            recordCsrfReject(req, "CSRF_HEADER_MISSING");
            writeCsrfError(resp, "CSRF token header 缺失，前端需在请求头加 X-XSRF-TOKEN");
            return;
        }

        // timing-safe 比较：防止攻击者通过响应时间猜 token
        if (!java.security.MessageDigest.isEqual(
                cookieToken.getBytes(), headerToken.getBytes())) {
            log.warn("🚫 CSRF 拦截：cookie 与 header 不匹配 ({} {})", method, path);
            recordCsrfReject(req, "CSRF_TOKEN_MISMATCH");
            writeCsrfError(resp, "CSRF token 校验失败，cookie 与 header 不一致");
            return;
        }

        // 校验通过
        chain.doFilter(req, resp);
    }

    /**
     * 是否安全方法（不修改服务器状态）
     */
    private boolean isSafeMethod(String method) {
        return SAFE_METHODS.contains(method.toUpperCase());
    }

    /**
     * 是否豁免路径
     */
    private boolean isExempt(String path) {
        if (EXEMPT_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写 403 CSRF 错误响应
     */
    private void writeCsrfError(HttpServletResponse resp, String message) throws IOException {
        resp.setStatus(403);
        resp.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of(
                "code", 403,
                "message", message,
                "success", false,
                "error", "CSRF_TOKEN_INVALID"
        );
        resp.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /**
     * V2.0.26 P2.4：记录 CSRF 拒绝事件（失败审计）
     *
     * @param req        HttpServletRequest
     * @param reasonCode 错误码（CSRF_COOKIE_MISSING / CSRF_HEADER_MISSING / CSRF_TOKEN_MISMATCH）
     */
    private void recordCsrfReject(HttpServletRequest req, String reasonCode) {
        try {
            String ip = getClientIp(req);
            String ua = req.getHeader("User-Agent");
            authAuditService.recordFailureWithMeta(
                    AuthAuditEvent.EventType.CSRF_REJECT,
                    null, null,
                    ip,
                    req.getRequestURI(), req.getMethod(),
                    reasonCode,
                    "CSRF token 校验失败",
                    Map.of(
                            "cookiePresent", req.getCookies() != null,
                            "headerPresent", req.getHeader(CsrfTokenService.HEADER_NAME) != null,
                            "userAgent", ua == null ? "unknown" : (ua.length() > 200 ? ua.substring(0, 200) : ua)
                    )
            );
        } catch (Exception e) {
            log.warn("CSRF 审计写入失败（不影响拦截）: {}", e.getMessage());
        }
    }

    /**
     * 提取客户端 IP（V2.0.71 PRD-1.3：统一走 ClientIpResolver，与业务层同源同值）
     */
    private String getClientIp(HttpServletRequest req) {
        return clientIpResolver.resolve(req);
    }
}
