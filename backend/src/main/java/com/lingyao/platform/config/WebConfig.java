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
        // 2026-08-31 V2.0.18 W5：新增 5 家子产品本地 dev 域
        //      （DINFO/POR-M 强耦合；GEOM/MPDM/AIDD/MED-PPT 按需）
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
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
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
