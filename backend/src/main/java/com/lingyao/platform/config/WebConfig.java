package com.lingyao.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 配置 — Bug-13 修复 CORS / Bug-16 修复 /admin 路由
 *
 * V2.0.31：CORS 提升为 CorsConfigurationSource Bean（filter 层）。
 * 原因：MVC 层 addCorsMappings 只对走完过滤链的请求生效；CsrfFilter/RateLimitFilter
 * 直接拒掉的 403 响应不带 Access-Control-Allow-Origin，浏览器会报 "Load failed"
 * 而非真实错误，前端无法提示原因。filter 层 CORS 让错误响应也带 CORS 头。
 *
 * V2.0.77.23 端口稳定性治理：CORS 白名单抽到 application.yml（app.cors.*）
 * 单一真源，关联 infra/port-allocation.md + infra/port-change-checklist.md §3.2
 * 改 origin：直接编辑 application.yml 的 app.cors.allowed-origin-patterns 列表
 * 无需改本文件。
 */
@Configuration
@EnableConfigurationProperties(WebConfig.CorsProperties.class)
public class WebConfig implements WebMvcConfigurer {

    /**
     * V2.0.77.23：CORS 配置项绑定（从 application.yml 读 app.cors.*）
     * Spring 通过类级 @EnableConfigurationProperties 把它注册为 Bean，
     * 构造器注入即可拿到绑定好的实例。
     */
    @ConfigurationProperties(prefix = "app.cors")
    public static class CorsProperties {
        private List<String> allowedOriginPatterns = List.of();
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        private List<String> allowedHeaders = List.of("*");
        private Boolean allowCredentials = true;
        private Long maxAge = 3600L;

        public List<String> getAllowedOriginPatterns() { return allowedOriginPatterns; }
        public void setAllowedOriginPatterns(List<String> v) { this.allowedOriginPatterns = v; }
        public List<String> getAllowedMethods() { return allowedMethods; }
        public void setAllowedMethods(List<String> v) { this.allowedMethods = v; }
        public List<String> getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(List<String> v) { this.allowedHeaders = v; }
        public Boolean getAllowCredentials() { return allowCredentials; }
        public void setAllowCredentials(Boolean v) { this.allowCredentials = v; }
        public Long getMaxAge() { return maxAge; }
        public void setMaxAge(Long v) { this.maxAge = v; }
    }

    private final CorsProperties corsProperties;

    public WebConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Bug-16 修复：/admin/ 转发到超管后台 HTML
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }

    /**
     * V2.0.31：filter 层 CORS 源 — Security http.cors() 自动检测此 Bean。
     * V2.0.77.23：白名单从 application.yml 的 app.cors.allowed-origin-patterns 读取
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(corsProperties.getAllowedOriginPatterns());
        config.setAllowedMethods(corsProperties.getAllowedMethods());
        config.setAllowedHeaders(corsProperties.getAllowedHeaders());
        config.setAllowCredentials(corsProperties.getAllowCredentials());
        config.setMaxAge(corsProperties.getMaxAge());

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
