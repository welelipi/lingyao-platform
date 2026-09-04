package com.lingyao.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SecurityHeadersFilter — V2.0.23 P2.1 阶段 C
 *
 * 为所有响应加 OWASP 推荐的 7 个安全响应头：
 * 1) X-Frame-Options: DENY               — 防 clickjacking
 * 2) X-Content-Type-Options: nosniff    — 防 MIME sniffing
 * 3) X-XSS-Protection: 1; mode=block    — 老浏览器 XSS 防护（无害）
 * 4) Strict-Transport-Security          — HSTS 强制 HTTPS（仅生产）
 * 5) Content-Security-Policy            — CSP 防 XSS（按 URL 类型差异化）
 * 6) Referrer-Policy                    — 防 referrer 泄漏
 * 7) Permissions-Policy                 — 限制浏览器 API
 *
 * URL 类型差异化（关键：不能让 CSP 把 H2 console / Vite dev 弄崩）：
 * - HTML 页面（/portal.html / /admin.html / /index.html 等）：完整 CSP
 * - 静态资源（/js/ /css/ /assets/ /images/）：宽松 CSP（允许 inline）
 * - API（/api/**）：不需要 CSP（返 JSON）
 * - H2 console：frame-options 必须 SAMEORIGIN（DENY 会卡 console）
 *
 * 参考 OWASP Secure Headers Project：
 * https://owasp.org/www-project-secure-headers/
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Value("${lingyao.security.headers.hsts.enabled:false}")
    private boolean hstsEnabled;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private boolean isDevProfile() {
        return activeProfile != null && activeProfile.startsWith("dev");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        String path = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }

        // ═══ 全局基础头（所有响应都加）═══
        // 1) X-Frame-Options（除 H2 console 外都 DENY）
        if (path.startsWith("/h2-console")) {
            resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        } else {
            resp.setHeader("X-Frame-Options", "DENY");
        }

        // 2) X-Content-Type-Options
        resp.setHeader("X-Content-Type-Options", "nosniff");

        // 3) X-XSS-Protection（老浏览器兼容）
        resp.setHeader("X-XSS-Protection", "1; mode=block");

        // 6) Referrer-Policy
        resp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // 7) Permissions-Policy（关闭摄像头/麦克风/地理等敏感 API）
        resp.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=(), " +
                "magnetometer=(), gyroscope=(), accelerometer=()");

        // 4) HSTS（仅生产 + HTTPS）
        if (hstsEnabled && !isDevProfile()) {
            resp.setHeader("Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains");
        }

        // 5) Content-Security-Policy（按 URL 类型差异化）
        String csp = buildCsp(path);
        if (csp != null) {
            resp.setHeader("Content-Security-Policy", csp);
        }

        chain.doFilter(req, resp);
    }

    /**
     * 按 URL 类型返回对应 CSP 策略
     */
    private String buildCsp(String path) {
        // API 不需要 CSP（返 JSON，无 inline script）
        if (path.startsWith("/api/")) {
            return null;
        }

        // H2 console（仅 dev）— 放宽避免 console 报错
        if (path.startsWith("/h2-console")) {
            return "default-src 'self'; " +
                   "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                   "style-src 'self' 'unsafe-inline'; " +
                   "img-src 'self' data:; " +
                   "frame-ancestors 'self'; " +
                   "form-action 'self';";
        }

        // 静态资源（/js /css /assets /images /fonts）
        if (path.startsWith("/js/") || path.startsWith("/css/") ||
            path.startsWith("/assets/") || path.startsWith("/images/") ||
            path.startsWith("/fonts/") || path.endsWith(".js") ||
            path.endsWith(".css") || path.endsWith(".png") ||
            path.endsWith(".jpg") || path.endsWith(".svg")) {
            return "default-src 'self'; " +
                   "script-src 'self' 'unsafe-inline'; " +
                   "style-src 'self' 'unsafe-inline'; " +
                   "img-src 'self' data:;";
        }

        // HTML 页面（完整 CSP）
        return "default-src 'self'; " +
               // 允许 self + unsafe-inline（兼容老 jQuery / Vite HMR）
               "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
               "style-src 'self' 'unsafe-inline'; " +
               "img-src 'self' data: https:; " +
               "font-src 'self' data:; " +
               "connect-src 'self'; " +
               "frame-ancestors 'none'; " +
               "base-uri 'self'; " +
               "form-action 'self'; " +
               "object-src 'none';";
    }
}