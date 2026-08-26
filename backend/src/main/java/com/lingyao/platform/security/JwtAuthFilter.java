package com.lingyao.platform.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * JWT 认证过滤器 — Phase 2 解耦版 + Phase 2.5 数据隔离加固
 *
 * 每次请求解析 Authorization: Bearer xxx
 * 将 CurrentUser 注入 SecurityContext + Request Attribute
 * 启用租户隔离 Hibernate @Filter（防跨租户泄露）
 *
 * 兼容策略：
 * - 新格式 token（含 tenant/identity/grants）→ 直接读新字段
 * - 老格式 token（只含 pcode/rcode）→ fallback 读老字段，但 CurrentUser 不再持有 pcode/rcode
 * - 过渡期：1 周（2026-08-19 后只支持新格式）
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TenantAwareUtil tenantAwareUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtil.parse(token);

                CurrentUser currentUser = new CurrentUser();
                currentUser.setUserId(claims.get("uid", Number.class) != null
                        ? claims.get("uid", Number.class).longValue() : null);
                currentUser.setUsername(claims.get("username", String.class));
                currentUser.setCompanyId(claims.get("cid", Number.class) != null
                        ? claims.get("cid", Number.class).longValue() : null);
                currentUser.setCompanyCode(claims.get("ccode", String.class));
                currentUser.setPlatformAdmin(Boolean.TRUE.equals(claims.get("adm", Boolean.class)));

                // === Phase 2 新增：从标准化断言读取 ===
                if (jwtUtil.isNewFormat(claims)) {
                    // 新格式：读 tenant/identity
                    Object tenantObj = claims.get("tenant");
                    Object identityObj = claims.get("identity");
                    Object grantsObj = claims.get("grants");

                    if (tenantObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> tenant = (Map<String, Object>) tenantObj;
                        Object name = tenant.get("name");
                        if (name != null) currentUser.setTenantName(name.toString());
                    }
                    if (identityObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> identity = (Map<String, Object>) identityObj;
                        Object displayName = identity.get("display_name");
                        if (displayName != null) currentUser.setDisplayName(displayName.toString());
                    }
                    // grants 暂存到 Request Attribute（业务用到时取）
                    if (grantsObj != null) {
                        request.setAttribute("grants", grantsObj);
                    }
                } else {
                    // 老格式 fallback：1 周过渡期
                    // pcode/rcode 不再写入 CurrentUser，保留在 Request Attribute 供可能的老业务读取
                    Object pcode = claims.get("pcode");
                    Object rcode = claims.get("rcode");
                    if (pcode != null) {
                        request.setAttribute("legacy_pcode", pcode);
                    }
                    if (rcode != null) {
                        request.setAttribute("legacy_rcode", rcode);
                    }
                }

                // 注入到 SecurityContext
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        currentUser, null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);

                // 同时注入到 ThreadLocal — 让 Service 层的 CurrentUser.get() 也能读取
                CurrentUser.set(currentUser);

                // 同时注入到 Request Attribute 方便 Controller 读取
                request.setAttribute("currentUser", currentUser);

                // === Phase 2.5 新增：自动启用租户隔离 Hibernate Filter ===
                // 仅对非平台超管启用，避免普通租户跨租户访问数据
                if (currentUser.getCompanyId() != null && !currentUser.isPlatformAdmin()) {
                    tenantAwareUtil.enableTenantFilter(currentUser.getCompanyId());
                }
            } catch (Exception e) {
                logger.debug("JWT 解析失败: " + e.getMessage());
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Phase 2.5：清理 Hibernate Filter，避免线程复用导致上下文错乱
            tenantAwareUtil.disableTenantFilter();
            // 清理 ThreadLocal，避免线程复用导致上下文错乱
            CurrentUser.clear();
        }
    }

    /**
     * 从 SecurityContext 获取当前用户
     */
    public static CurrentUser getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser) {
            return (CurrentUser) auth.getPrincipal();
        }
        return null;
    }
}