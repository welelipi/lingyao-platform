package com.lingyao.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingyao.platform.entity.AuthAuditEvent;
import com.lingyao.platform.service.AuthAuditService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * RateLimitFilter — V2.0.24 P2.2 阶段 C
 *
 * 拦截认证相关接口（POST /api/auth/*），按 endpoint + IP/user 限流
 *
 * 限流策略（默认）：
 * - POST /api/auth/login             → 每 IP 5次/分钟（防爆破）
 * - POST /api/auth/forgot-password  → 每 IP 3次/小时（防 spam）
 * - POST /api/auth/refresh          → 每 user 60次/小时（防滥用）
 * - POST /api/auth/change-password  → 每 user 3次/小时（防异常改密）
 *
 * 命中限流 → 429 Too Many Requests + X-RateLimit-* 头
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)  // 在 SecurityHeadersFilter 之后
public class RateLimitFilter extends OncePerRequestFilter {

    @Autowired
    private RateLimiterService rateLimiterService;

    // V2.0.71 PRD-1.3：统一 IP 口径（仅 forward-headers-strategy 启用时采信 XFF）
    @Autowired
    private ClientIpResolver clientIpResolver;

    // V2.0.24 P2.2 修复：自己解析 token 拿 userId（不依赖 JwtAuthFilter 设置 CurrentUser）
    @Autowired
    private JwtUtil jwtUtil;

    // V2.0.26 P2.4：限流命中时记录安全审计事件
    @Autowired
    private AuthAuditService authAuditService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── 限流配置常量 ──────────────────────────────
    private static final int LOGIN_MAX_PER_MIN = 5;
    private static final long LOGIN_WINDOW_MS = 60 * 1000L;

    private static final int FORGOT_MAX_PER_HOUR = 3;
    private static final long FORGOT_WINDOW_MS = 60 * 60 * 1000L;

    private static final int REFRESH_MAX_PER_HOUR = 60;
    private static final long REFRESH_WINDOW_MS = 60 * 60 * 1000L;

    private static final int CHANGE_PWD_MAX_PER_HOUR = 3;
    private static final long CHANGE_PWD_WINDOW_MS = 60 * 60 * 1000L;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        String method = req.getMethod();
        String path = req.getRequestURI();

        // 仅拦截 POST /api/auth/* 的状态变动接口
        if (!"POST".equalsIgnoreCase(method) || !path.startsWith("/api/auth/")) {
            chain.doFilter(req, resp);
            return;
        }

        // ─── 1) /api/auth/login（按 IP）───
        if (path.equals("/api/auth/login")) {
            String clientIp = getClientIp(req);
            if (!rateLimiterService.allow("login", clientIp, LOGIN_MAX_PER_MIN, LOGIN_WINDOW_MS)) {
                writeRateLimitResponse(resp, "login", clientIp, LOGIN_MAX_PER_MIN, LOGIN_WINDOW_MS);
                return;
            }
        }

        // ─── 2) /api/auth/forgot-password（按 IP）───
        else if (path.equals("/api/auth/forgot-password")) {
            String clientIp = getClientIp(req);
            if (!rateLimiterService.allow("forgot-password", clientIp, FORGOT_MAX_PER_HOUR, FORGOT_WINDOW_MS)) {
                writeRateLimitResponse(resp, "forgot-password", clientIp, FORGOT_MAX_PER_HOUR, FORGOT_WINDOW_MS);
                return;
            }
        }

        // ─── 3) /api/auth/refresh（按 user，body 含 refreshToken）───
        // 注：refresh 端点本身是公开的，所以 user 信息需从 refreshToken hash 反查
        // 简化：先用 IP 做粗粒度限流（防批量扫 token）
        // V2.0.25+ 改进：解析 body 拿 refreshToken → 查 user_id
        else if (path.equals("/api/auth/refresh")) {
            String clientIp = getClientIp(req);
            if (!rateLimiterService.allow("refresh", clientIp, REFRESH_MAX_PER_HOUR, REFRESH_WINDOW_MS)) {
                writeRateLimitResponse(resp, "refresh", clientIp, REFRESH_MAX_PER_HOUR, REFRESH_WINDOW_MS);
                return;
            }
        }

        // ─── 4) /api/auth/change-password（按 user，从 Authorization 头解析）───
        else if (path.equals("/api/auth/change-password")) {
            Long userId = parseUserIdFromAuth(req);
            if (userId == null) {
                // 未登录或 token 无效 → 让后续 Spring Security 拦截（401），不限流
                chain.doFilter(req, resp);
                return;
            }
            String userKey = "user:" + userId;
            if (!rateLimiterService.allow("change-password", userKey, CHANGE_PWD_MAX_PER_HOUR, CHANGE_PWD_WINDOW_MS)) {
                writeRateLimitResponse(resp, "change-password", userKey, CHANGE_PWD_MAX_PER_HOUR, CHANGE_PWD_WINDOW_MS);
                return;
            }
        }

        chain.doFilter(req, resp);
    }

    /**
     * 写 429 响应 + 标准 X-RateLimit-* 头
     */
    private void writeRateLimitResponse(HttpServletResponse resp, String endpoint, String key,
                                         int maxRequests, long windowMs) throws IOException {
        int remaining = rateLimiterService.getRemaining(endpoint, key, maxRequests, windowMs);
        long resetMs = rateLimiterService.getResetMs(endpoint, key, windowMs);
        long retryAfterSec = Math.max(1, (resetMs - System.currentTimeMillis()) / 1000);

        resp.setStatus(429);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("X-RateLimit-Limit", String.valueOf(maxRequests));
        resp.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        resp.setHeader("X-RateLimit-Reset", String.valueOf(resetMs / 1000));
        resp.setHeader("Retry-After", String.valueOf(retryAfterSec));

        Map<String, Object> body = Map.of(
            "code", 429,
            "message", "请求过于频繁，请稍后再试",
            "success", false,
            "retryAfterSeconds", retryAfterSec
        );
        resp.getWriter().write(objectMapper.writeValueAsString(body));

        // V2.0.26 P2.4：限流命中 → 记录安全审计事件
        // 注：本次请求已写 429，所以从 latestRateLimitedRequest 上下文拿不到，用 path + key 反推
        // 简化处理：审计里只记录端点 + key 类型 + IP（key 中 "user:" 前缀表示 user 维度）
        recordRateLimitHit(endpoint, key, maxRequests, windowMs, retryAfterSec);
    }

    /**
     * V2.0.26 P2.4：记录限流命中
     *
     * @param endpoint      /api/auth/login 等
     * @param key           限流键（IP 或 user:123 形式）
     * @param maxRequests   窗口内最大请求数
     * @param windowMs      窗口长度（毫秒）
     * @param retryAfterSec Retry-After 秒数
     */
    private void recordRateLimitHit(String endpoint, String key, int maxRequests, long windowMs, long retryAfterSec) {
        try {
            // 解析 userId（key 为 "user:123" 形式时）
            Long userId = null;
            String displayKey = key;
            if (key != null && key.startsWith("user:")) {
                try {
                    userId = Long.parseLong(key.substring(5));
                    displayKey = "user:" + userId;
                } catch (NumberFormatException ignored) {
                    // key 格式异常，原样记录
                }
            }
            authAuditService.recordFailureWithMeta(
                    AuthAuditEvent.EventType.RATE_LIMIT_HIT,
                    userId, null,  // userId 可空，username 从 ip 反查不到
                    displayKey,    // IP 直接放 ip_address 字段（key 维度）
                    "/api/auth/" + endpoint, "POST",
                    "RATE_LIMIT_EXCEEDED",
                    "请求过于频繁，已被限流",
                    Map.of(
                            "endpoint", endpoint,
                            "limit", maxRequests,
                            "windowMs", windowMs,
                            "retryAfterSec", retryAfterSec
                    )
            );
        } catch (Exception e) {
            // 审计失败不影响限流响应
            org.slf4j.LoggerFactory.getLogger(RateLimitFilter.class)
                    .warn("限流审计写入失败（不影响 429 响应）: {}", e.getMessage());
        }
    }

    /**
     * 提取客户端 IP（V2.0.71 PRD-1.3：统一走 ClientIpResolver，与业务层同源同值）
     */
    private String getClientIp(HttpServletRequest req) {
        return clientIpResolver.resolve(req);
    }

    /**
     * 从 Authorization 头解析 userId（不依赖 JwtAuthFilter 设置的 CurrentUser）
     * @return userId，失败返回 null
     */
    private Long parseUserIdFromAuth(HttpServletRequest req) {
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            Claims claims = jwtUtil.parse(token);
            // V2.0.16 W1 JWT claims "uid" 或 subject
            Object uidObj = claims.get("uid");
            if (uidObj != null) {
                return Long.parseLong(uidObj.toString());
            }
            // fallback: subject
            String sub = claims.getSubject();
            if (sub != null) {
                return Long.parseLong(sub);
            }
        } catch (Exception e) {
            // token 无效 → null
        }
        return null;
    }
}