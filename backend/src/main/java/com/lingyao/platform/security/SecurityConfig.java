package com.lingyao.platform.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    // V2.0.23 P2.1 阶段 C：Security headers（X-Frame-Options / CSP / HSTS 等）
    @Autowired
    private SecurityHeadersFilter securityHeadersFilter;

    // V2.0.24 P2.2 阶段 C：限流（login/refresh/forgot-password/change-password）
    @Autowired
    private RateLimitFilter rateLimitFilter;

    // V2.0.25 P2.3 阶段 C：CSRF 防护（Double-Submit Cookie）
    @Autowired
    private CsrfFilter csrfFilter;

    // V2.0.31：filter 层 CORS 源（WebConfig 提供，白名单含主站/子平台/生产域名）
    @Autowired
    private org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))   // V2.0.31：filter 层 CORS（错误响应也带 CORS 头）
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    // ── 公开 API（不需登录） ─────
                    "/api/health",
                    "/api/version",
                    "/api/_diag/**",            // V2.0.3: 开发者诊断 endpoint（无需 JWT 自检版本/PID/启动时间）
                    "/api/public/**",
                    "/api/auth/login",
                    "/api/auth/logout",           // V2.0.20 P1 阶段 A：logout 幂等，允许未登录调用（前端清 token）
                    "/api/auth/refresh",          // V2.0.21 P1.4 阶段 B：refresh 本身用 refresh_token 鉴权（不需 access_token）
                    "/api/auth/forgot-password",  // V2.0.21 P1.5 阶段 B：忘记密码（公开）
                    "/api/auth/reset-password",   // V2.0.21 P1.5 阶段 B：重置密码（公开，token 鉴权）
                    "/api/auth/register",
                    "/api/registrations",         // 公开报名提交
                    "/api/registrations/stats",   // 公开统计（仅数量）
                    "/api/invitations/token/*",   // 公开：校验邀请 token
                    "/api/invitations/redeem",    // 公开：通过邀请注册
                    "/api/feedback",              // 公开：反馈提交（前端兜底本地存储）
                    "/api/sub-product/*/verify-link",  // V2.0.19 W6 阶段 2：专属链接验签（子平台调用，员工可能还没账号）
                    "/api/internal/**",           // V2.0.39：服务间接口（grants sync），接口内自验 service JWT（audience=lingyao-service）
                    // ── 静态资源（全部放行） ─────
                    "/",
                    "/index.html",
                    "/portal.html",
                    "/admin.html",
                    "/invite.html",
                    "/404.html",
                    "/admin/**",
                    "/static/**",
                    "/css/**",
                    "/js/**",
                    "/assets/**",
                    "/error",
                    "/h2-console/**"
                ).permitAll()
                // ── 业务 API 必须登录（其他业务鉴权由 @PreAuthorize / requireXxx() 处理） ─────
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .headers(h -> h.disable())  // V2.0.23 P2.1：禁用 Spring Security 默认 headers，由 SecurityHeadersFilter 统一管理
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((req, resp, ex) -> {
                    resp.setStatus(401);
                    resp.setContentType("application/json;charset=UTF-8");
                    resp.getWriter().write("{\"code\":401,\"message\":\"未登录或会话已过期\",\"success\":false}");
                })
                .accessDeniedHandler((req, resp, ex) -> {
                    resp.setStatus(403);
                    resp.setContentType("application/json;charset=UTF-8");
                    resp.getWriter().write("{\"code\":403,\"message\":\"无权访问该资源\",\"success\":false}");
                })
            )
            .addFilterBefore(securityHeadersFilter, org.springframework.web.filter.CorsFilter.class)  // V2.0.23 P2.1：安全响应头（CSP/HSTS/X-Frame-Options）
            .addFilterAfter(csrfFilter, org.springframework.web.filter.CorsFilter.class)  // V2.0.25 P2.3：CSRF（Double-Submit Cookie，在头与限流之间）
            .addFilterBefore(rateLimitFilter, CsrfFilter.class)  // V2.0.24 P2.2：限流，在 CSRF 之后
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
