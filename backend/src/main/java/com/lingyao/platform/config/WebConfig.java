package com.lingyao.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置 — Bug-13 修复 CORS / Bug-16 修复 /admin 路由
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Bug-16 修复：/admin/ 转发到超管后台 HTML
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Bug-13 修复：CORS 白名单（不再使用 *）
        // 2026-08-26 新增生产环境入口（公网 IP + 备案后域名）
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        // ── 本地开发 ─────
                        "http://localhost:8765",
                        "http://127.0.0.1:8765",
                        "http://localhost:9091",
                        "http://127.0.0.1:9091",
                        // ── 腾讯云 CVM 生产（公网 IP） ─────
                        "http://118.195.197.15",
                        "http://118.195.197.15:*",
                        // ── 备案后域名（ICP 通过后启用） ─────
                        "https://www.lydmed.com",
                        "https://lydmed.com",
                        "http://www.lydmed.com",
                        "http://lydmed.com"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
