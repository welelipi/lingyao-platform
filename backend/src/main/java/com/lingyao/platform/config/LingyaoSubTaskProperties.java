package com.lingyao.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子任务路由配置（V2.0.11 D-1 引入）
 *
 * 取代 data.sql 里硬编码的 base_url / health_url
 *
 * 用法：
 *   application.yml:
 *     lingyao:
 *       subtask:
 *         routes:
 *           geo:
 *             base-url: http://10.0.0.5:8090
 *             health-url: http://10.0.0.5:8090/api/health
 *             entry-path: /subtask/geo
 *
 *   环境变量覆盖（部署时注入）：
 *     LINGYAO_SUBTASK_GEO_BASE_URL=http://10.0.0.5:8090
 *     LINGYAO_SUBTASK_GEO_HEALTH_URL=http://10.0.0.5:8090/api/health
 *     LINGYAO_SUBTASK_GEO_ENTRY_PATH=/subtask/geo
 *
 * SubTaskController.enter() 优先读本类的配置；fallback 才读 sub_task 表的 base_url
 * （保留 data.sql 字段向后兼容 dev 环境快速回滚）
 *
 * @since V2.0.11 2026-08-29 D-1 上传远端代码库前的稳定性 fix
 */
@Component
@ConfigurationProperties(prefix = "lingyao.subtask")
public class LingyaoSubTaskProperties {

    /**
     * key: 产品 code 小写（geo / hpd / aidd / dinfo / porm）
     * value: 该产品的路由配置
     */
    private Map<String, Route> routes = new LinkedHashMap<>();

    public Map<String, Route> getRoutes() { return routes; }
    public void setRoutes(Map<String, Route> routes) { this.routes = routes; }

    /**
     * 单个产品的路由配置
     */
    public static class Route {
        private String baseUrl;
        private String healthUrl;
        private String entryPath;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getHealthUrl() { return healthUrl; }
        public void setHealthUrl(String healthUrl) { this.healthUrl = healthUrl; }
        public String getEntryPath() { return entryPath; }
        public void setEntryPath(String entryPath) { this.entryPath = entryPath; }

        @Override
        public String toString() {
            return "Route{baseUrl='" + baseUrl + "', healthUrl='" + healthUrl +
                    "', entryPath='" + entryPath + "'}";
        }
    }

    /**
     * 根据产品 code 取路由；code 大小写不敏感
     */
    public Route getRoute(String productCode) {
        if (productCode == null || routes == null) return null;
        return routes.get(productCode.toLowerCase());
    }
}
