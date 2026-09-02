package com.lingyao.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置 — Bug-13 修复 CORS / Bug-16 修复 /admin 路由
 *
 * V2.0.31：CORS 提升为 CorsConfigurationSource Bean（filter 层）。
 * 原因：MVC 层 addCorsMappings 只对走完过滤链的请求生效；CsrfFilter/RateLimitFilter
 * 直接拒掉的 403 响应不带 Access-Control-Allow-Origin，浏览器会报 "Load failed"
 * 而非真实错误，前端无法提示原因。filter 层 CORS 让错误响应也带 CORS 头。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Bug-16 修复：/admin/ 转发到超管后台 HTML
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }

    /**
     * V2.0.31：filter 层 CORS 源 — Security http.cors() 自动检测此 Bean。
     * 白名单与原 MVC 层完全一致（含主站/5 子平台 dev/CVM 生产/备案域名）。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(java.util.List.of(
                // ── 本地主站 ─────
                "http://localhost:8765",
                "http://127.0.0.1:8765",
                "http://localhost:9091",
                "http://127.0.0.1:9091",
                // ── DINFO 辰录（强耦合 · 配合登录 P0）─────
                "http://localhost:5181",
                "http://127.0.0.1:5181",
                "http://localhost:8281",
                "http://127.0.0.1:8281",
                // ── POR-M 明枢（强耦合 · 跟主站同窗口推送）─────
                "http://localhost:8280",
                "http://127.0.0.1:8280",
                "http://localhost:3190",
                "http://127.0.0.1:3190",
                // ── GEOM 棱镜 ─────
                "http://localhost:5180",
                "http://127.0.0.1:5180",
                "http://localhost:5182",
                "http://127.0.0.1:5182",
                "http://localhost:8090",
                "http://127.0.0.1:8090",
                // ── MPDM 皓元 ─────
                "http://localhost:3100",
                "http://127.0.0.1:3100",
                "http://localhost:8100",
                "http://127.0.0.1:8100",
                // ── AIDD 源策 ─────
                "http://localhost:13000",
                "http://127.0.0.1:13000",
                "http://localhost:18080",
                "http://127.0.0.1:18080",
                // ── MED-PPT ─────
                "http://localhost:19880",
                "http://127.0.0.1:19880",
                "http://localhost:19881",
                "http://127.0.0.1:19881",
                // ── 腾讯云 CVM 生产（公网 IP） ─────
                "http://118.195.197.15",
                "http://118.195.197.15:*",
                // ── 备案后域名（ICP 通过后启用） ─────
                "https://www.lydmed.com",
                "https://lydmed.com",
                "http://www.lydmed.com",
                "http://lydmed.com"
        ));
        config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // V2.0.31：白名单已迁移到 corsConfigurationSource()（filter 层），此处保留空实现
        // 避免两处维护同一份白名单。MVC 层会复用全局 CorsConfigurationSource。
    }

    /**
     * V2.0.31：全局 CorsFilter（order = HIGHEST+5）。
     *
     * 关键：CsrfFilter / SecurityHeadersFilter 是 @Component 全局过滤器
     * （order HIGHEST+15 / +10），先于 Spring Security 过滤链执行——它们拒掉的
     * 403 响应到不了 Security 链内的 CorsFilter，浏览器会因无 CORS 头报
     * "Load failed" 而看不到真实错误。把 CorsFilter 注册到 HIGHEST+5，
     * 保证所有 /api 响应（含被拒的 403/401）都带 CORS 头。
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<org.springframework.web.filter.CorsFilter> globalCorsFilter() {
        org.springframework.boot.web.servlet.FilterRegistrationBean<org.springframework.web.filter.CorsFilter> reg =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                        new org.springframework.web.filter.CorsFilter(corsConfigurationSource()));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 5);
        reg.setName("globalCorsFilter");
        return reg;
    }
}
