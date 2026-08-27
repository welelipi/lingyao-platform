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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    // ── 公开 API（不需登录） ─────
                    "/api/health",
                    "/api/version",
                    "/api/_diag/**",            // V2.0.3: 开发者诊断 endpoint（无需 JWT 自检版本/PID/启动时间）
                    "/api/public/**",
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/registrations",         // 公开报名提交
                    "/api/registrations/stats",   // 公开统计（仅数量）
                    "/api/invitations/token/*",   // 公开：校验邀请 token
                    "/api/invitations/redeem",    // 公开：通过邀请注册
                    "/api/feedback",              // 公开：反馈提交（前端兜底本地存储）
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
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))  // H2 console 需要
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
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
