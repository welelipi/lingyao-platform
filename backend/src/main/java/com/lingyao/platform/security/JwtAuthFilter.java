package com.lingyao.platform.security;

import com.lingyao.platform.entity.AuthAuditEvent;
import com.lingyao.platform.repository.TokenBlacklistRepository;
import com.lingyao.platform.service.AuthAuditService;
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

    // V2.0.20 P1 阶段 A：黑名单校验
    @Autowired
    private TokenBlacklistRepository tokenBlacklistRepository;

    // V2.0.26 P2.4：黑名单命中时记录安全审计事件
    @Autowired
    private AuthAuditService authAuditService;

    // V2.0.71 PRD-1.3：统一 IP 口径
    @Autowired
    private ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtil.parse(token);

                // V2.0.20 P1 阶段 A：黑名单校验（logout / password_changed / admin_revoke）
                String jti = claims.getId();
                if (jti != null && tokenBlacklistRepository.existsByJti(jti)) {
                    logger.debug("JWT 已被吊销 (jti=" + jti + ")");
                    // V2.0.26 P2.4：黑名单命中 → 记录安全审计事件（关键攻击信号）
                    recordBlacklistHit(claims, request);
                    // 不抛异常 → 让后续接口返回 401，由 GlobalExceptionHandler 统一处理
                    // 这里只清掉 SecurityContext，跳过设置 CurrentUser
                    filterChain.doFilter(request, response);
                    return;
                }

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

    /**
     * V2.0.26 P2.4：记录黑名单命中（异常使用已登出 token）
     *
     * 典型场景：
     * - 用户登出后，前端仍用旧 token 发请求（应该清掉 tokenStorage）
     * - 攻击者截获已登出的 access_token 试图使用（合法但已无效 → 审计）
     * - refresh_token 撤销后，对应的 access_token 在 24h 有效期内被复用
     */
    private void recordBlacklistHit(Claims claims, HttpServletRequest request) {
        try {
            String ip = getClientIp(request);
            Long userId = claims.get("uid", Number.class) != null
                    ? claims.get("uid", Number.class).longValue() : null;
            String username = claims.get("username", String.class);
            authAuditService.recordFailureWithMeta(
                    AuthAuditEvent.EventType.TOKEN_BLACKLIST_HIT,
                    userId, username, ip,
                    request.getRequestURI(), request.getMethod(),
                    "BLACKLIST_HIT",
                    "已登出的 access_token 被复用",
                    Map.of(
                            "jti", claims.getId() == null ? "unknown" : claims.getId(),
                            "exp", claims.getExpiration() == null ? "unknown" : claims.getExpiration().toString(),
                            "userAgent", request.getHeader("User-Agent") == null ? "unknown"
                                    : request.getHeader("User-Agent").substring(0,
                                            Math.min(200, request.getHeader("User-Agent").length()))
                    )
            );
        } catch (Exception e) {
            // 审计失败不影响主流程
            logger.warn("黑名单审计写入失败（不影响 401 响应）: " + e.getMessage());
        }
    }

    /**
     * 提取客户端 IP（V2.0.71 PRD-1.3：统一走 ClientIpResolver，与业务层同源同值）
     */
    private String getClientIp(HttpServletRequest req) {
        return clientIpResolver.resolve(req);
    }
}