package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "service", "lingyao-platform",
                "version", "1.0.0",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
