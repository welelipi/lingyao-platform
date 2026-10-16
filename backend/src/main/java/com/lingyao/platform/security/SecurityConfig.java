package com.lingyao.platform.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
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

    // V2.0.71 PRD-2.7：_diag 与 h2-console 按 profile 收敛（dev 保留，private/staging/prod 关闭）
    @Autowired
    private Environment env;

    private boolean isDevProfile() {
        for (String p : env.getActiveProfiles()) {
            if (p.startsWith("dev")) return true;
        }
        return false;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        boolean dev = isDevProfile();
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))   // V2.0.31：filter 层 CORS（错误响应也带 CORS 头）
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                var chain = auth
                    .requestMatchers(
                        // ── 公开 API（不需登录） ─────
                        "/api/health",
                        "/api/version",
                        "/api/public/**",
                        "/api/auth/login",
                        "/api/auth/captcha",          // V2.0.66：图形验证码出题（登录前置，公开）
                        // V2.0.78.26：JWT 密钥指纹（公开）。
                        // 必须免鉴权 —— 它的用途正是「token 已因密钥轮换而失效时仍能读到指纹」，
                        // 若要求鉴权则该场景下必然 401，机制形同虚设。
                        // 只回 SHA-256 前 16 hex 摘要，不泄露密钥本身。
                        "/api/auth/jwt-fingerprint",
                        "/api/auth/device-otp/send",  // V2.0.69 D：新设备登录邮箱码发送（登录前置，公开）
                        "/api/auth/logout",           // V2.0.20 P1 阶段 A：logout 幂等，允许未登录调用（前端清 token）
                        "/api/auth/refresh",          // V2.0.21 P1.4 阶段 B：refresh 本身用 refresh_token 鉴权（不需 access_token）
                        "/api/auth/forgot-password",  // V2.0.21 P1.5 阶段 B：忘记密码（公开）
                        "/api/auth/reset-password",   // V2.0.21 P1.5 阶段 B：重置密码（公开，token 鉴权）
                        "/api/auth/register",
                        // V2.0.78.27 安全（L-01）：/api/registrations 移出「无方法限定的路径白名单」。
                        // 根因：本白名单是「路径」匹配、不带 HTTP 方法限定，因此
                        // 同一个 "/api/registrations" 字符串同时覆盖了 POST 提交与 GET 列表
                        // （RegistrationController 里 list 就挂在 @GetMapping 根路径上），
                        // 实测零 header / 零 cookie 即可拉到全站报名姓名/手机/邮箱。
                        // 改为下方「精确方法匹配」白名单，只放行真正公开的两条。
                        "/api/invitations/token/*",   // 公开：校验邀请 token
                        "/api/invitations/redeem",    // 公开：通过邀请注册
                        "/api/feedback",              // 公开：反馈提交（前端兜底本地存储）
                        "/api/trial/register",        // V2.0.78.0：trial 公开注册（① 纯公开 · D-5 拍板）
                        "/api/trial/check",           // V2.0.78.0：trial 手机号重复检测（公开 · 前端表单实时校验）
                        "/api/sub-product/*/verify-link",  // V2.0.19 W6 阶段 2：专属链接验签（子平台调用，员工可能还没账号）
                        "/api/internal/**",           // V2.0.39：服务间接口（grants sync），接口内自验 service JWT（audience=lingyao-service）
                        "/api/sso/**",                // V2.0.48：公司 SSO 发起+回调（员工未登录/飞书回跳均无会话；login 校验 company_code，callback 验 state+code，均自防伪造）
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
                        "/error"
                    ).permitAll();
                // V2.0.78.27 安全（L-01）：报名接口的「精确方法匹配」公开白名单
                //  · POST /api/registrations       → 公开报名提交（游客/未登录）
                //  · GET  /api/registrations/stats → 公开统计（只回各状态数量，无 PII）
                // 其余 /api/registrations/** （GET 列表、GET /{id}、PUT /{id}/status）
                // 不再命中 permitAll，落到下方 /api/** authenticated，需要登录 + 超管守卫。
                //
                // 为什么不用 "/api/registrations/**" 之类的通配：那会重新把列表/详情放开。
                // 为什么不用 MvcRequestMatcher：路径会被 servlet mapping 前后缀改写，
                // 容易与实际暴露的 URL 不一致；字面量 + HttpMethod 是最保守的写法。
                //
                // OPTIONS 预检：CorsFilter 在 Security 链之前短路 OPTIONS（HttpMethod.OPTIONS
                // 时不落到这些 matcher，浏览器预检由 WebConfig.globalCorsFilter 直接应答），
                // 故无需为预检单开 permitAll。
                chain = chain
                    .requestMatchers(HttpMethod.POST, "/api/registrations").permitAll()
                    .requestMatchers(HttpMethod.GET,  "/api/registrations/stats").permitAll();
                if (dev) {
                    // PRD-2.7：dev 保留 _diag 匿名自检（E2E 依赖 /api/_diag/version）与 h2-console
                    chain = chain
                        .requestMatchers("/api/_diag/**").permitAll()
                        .requestMatchers("/h2-console/**").permitAll();
                }
                // 非 dev（private/staging/prod）：_diag 需认证（落入下方 /api/** authenticated），
                // h2-console 不放行且 spring.h2.console.enabled=false（各 profile yml 已显式关闭）
                chain
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll();
            })
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
