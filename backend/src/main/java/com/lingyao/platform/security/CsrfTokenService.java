package com.lingyao.platform.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * CsrfTokenService — V2.0.25 P2.3 阶段 C
 *
 * CSRF Token 生成 + Cookie 管理（Double-Submit Cookie 模式）
 *
 * 核心设计：
 * 1. 任何 GET 请求如果发现客户端没有 XSRF-TOKEN cookie，就种一个
 * 2. 前端 JS 读 cookie → 把它放进 X-XSRF-TOKEN header 提交
 * 3. 后端 CsrfFilter 校验 cookie 和 header 必须一致（且都存在）
 *
 * Token 长度：32 字节 SecureRandom → Base64 URL-safe（43 字符）
 * 安全性：256 bits 熵，暴力破解不可能
 *
 * Cookie 属性：
 * - HttpOnly: false（**必须**让 JS 可读，才能放到 header）
 * - Path: /
 * - Max-Age: 24h（长会话足够）
 * - SameSite: Lax（防跨站 POST，浏览器默认 Lax 也是 Lax，CSRF 防御 80% 由它承担）
 * - Secure: false（dev 是 http；生产 HTTPS 时建议改成 true）
 *
 * 为什么用 Double-Submit Cookie 而不是 Spring Security 内置 CsrfTokenRepository：
 * - 凌瑶前端是 SPA + JWT（localStorage），不是 form 表单 + session cookie
 * - Spring Security 默认 CSRF 走 Session，且依赖 _csrf 参数，适配成本高
 * - Double-Submit 是 OWASP 推荐用于无状态 API 的方案，跟我们的架构契合
 */
@Component
public class CsrfTokenService {

    /** Cookie / Header 名称（前端需要知道） */
    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";

    /** Token 字节数（256 bits） */
    private static final int TOKEN_BYTES = 32;

    /** Cookie Max-Age（24 小时） */
    private static final int COOKIE_MAX_AGE = 24 * 3600;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成新的 CSRF token（Base64 URL-safe，43 字符）
     */
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 从请求中读 XSRF-TOKEN cookie
     *
     * @return token 值，未找到返回 null
     */
    public String getTokenFromCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /**
     * 设置 XSRF-TOKEN cookie 到响应
     *
     * @param resp  HttpServletResponse
     * @param token token 值（generateToken() 生成）
     */
    public void setTokenCookie(HttpServletResponse resp, String token) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setHttpOnly(false);  // 必须 false：JS 要读出来放进 header
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setSecure(false);    // dev http；生产 HTTPS 应改为 true（通过 yml 配置）
        // SameSite 通过属性设置（Servlet 6+ 才原生支持 setAttribute("SameSite", ...)，
        // Tomcat 10 默认支持；老版本可走 Set-Cookie header 手工拼接）
        try {
            cookie.setAttribute("SameSite", "Lax");
        } catch (IllegalArgumentException ignored) {
            // 老 Servlet 版本不支持 setAttribute，SameSite 不设置（依赖浏览器默认 Lax）
        }
        resp.addCookie(cookie);
    }

    /**
     * 便捷方法：若 cookie 缺失则生成并写入响应
     *
     * @return 客户端当前的 token（已有或新生成的）
     */
    public String ensureTokenCookie(HttpServletRequest req, HttpServletResponse resp) {
        String existing = getTokenFromCookie(req);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String newToken = generateToken();
        setTokenCookie(resp, newToken);
        return newToken;
    }
}
