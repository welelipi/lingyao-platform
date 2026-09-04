package com.lingyao.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * V2.0.71 PRD-1.3：统一客户端 IP 取值口径（单一实现）
 *
 * 规则：仅当 server.forward-headers-strategy 显式启用（非 none/非空）时，
 * 才采信 X-Forwarded-For 第一段（反代部署场景）；否则一律 request.getRemoteAddr()。
 *
 * 背景：此前 RateLimitFilter/CsrfFilter/JwtAuthFilter 优先采信 XFF（dev 直连可伪造绕过限流），
 * 而 AuthController 等业务层一律 getRemoteAddr（反代下全变 127.0.0.1）——同请求两种 IP 口径。
 * 统一后：同一请求在 Filter 链与业务层取到同一个 IP。
 */
@Component
public class ClientIpResolver {

    private final Environment environment;

    public ClientIpResolver(Environment environment) {
        this.environment = environment;
    }

    /**
     * 解析客户端真实 IP
     * @param req 当前请求
     * @return 客户端 IP（Filter 链与业务层同源同值）
     */
    public String resolve(HttpServletRequest req) {
        if (forwardHeadersEnabled() ) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // X-Forwarded-For 格式："client, proxy1, proxy2"，第一段是真实客户端 IP
                return xff.split(",")[0].trim();
            }
        }
        return req.getRemoteAddr();
    }

    private boolean forwardHeadersEnabled() {
        String strategy = environment.getProperty("server.forward-headers-strategy");
        return strategy != null && !strategy.isBlank() && !"none".equalsIgnoreCase(strategy);
    }
}
