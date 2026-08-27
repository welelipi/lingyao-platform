package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本号 endpoint（公开，无鉴权）
 *
 * 两个端点：
 * - GET /api/version        → 基础版本信息（前端首页底部展示用）
 * - GET /api/_diag/version  → 增强诊断信息（开发者自检：PID/启动时间/JVM 运行时长/Heap），
 *                            V2.0.3 加 permitAll 白名单，无需 JWT
 *
 * 标准格式：
 * {
 *   "code": 0,
 *   "data": {
 *     "service": "lingyao-platform",
 *     "version": "2.0.0",
 *     "release": "stable",
 *     "build_time": "2026-08-27T12:00:00Z",
 *     "git_commit": "c0089d0",
 *     "sso_protocol": "1.0"
 *   }
 * }
 */
@RestController
public class VersionController {

    private static final long START_TIME_MS = System.currentTimeMillis();

    @Value("${app.version:1.0.0}")
    private String appVersion;

    @Value("${app.release:stable}")
    private String appRelease;

    @Value("${app.build-time:}")
    private String appBuildTime;

    @Value("${app.git-commit:dev}")
    private String appGitCommit;

    @Value("${sso.protocol-version:1.0}")
    private String ssoProtocolVersion;

    @GetMapping("/api/version")
    public ApiResponse<Map<String, Object>> version() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "lingyao-platform");
        data.put("version", appVersion);
        data.put("release", appRelease);
        data.put("build_time", appBuildTime.isEmpty()
                ? OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                : appBuildTime);
        data.put("git_commit", appGitCommit);
        data.put("sso_protocol", ssoProtocolVersion);
        return ApiResponse.ok(data);
    }

    /**
     * V2.0.3 新增：开发者诊断 endpoint
     * 相比 /api/version 多返回：
     * - pid          JVM 进程 ID（自检时确认是否在跑新 jar）
     * - started_at   JVM 启动时间（精确到秒）
     * - uptime_sec   运行时长（秒）
     * - heap_used_mb 已用堆内存（MB）
     * - heap_max_mb  最大堆内存（MB）
     */
    @GetMapping("/api/_diag/version")
    public ApiResponse<Map<String, Object>> diagVersion() {
        Runtime rt = Runtime.getRuntime();
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long heapMax = rt.maxMemory() / (1024 * 1024);
        long uptimeSec = (System.currentTimeMillis() - START_TIME_MS) / 1000;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "lingyao-platform");
        data.put("version", appVersion);
        data.put("release", appRelease);
        data.put("build_time", appBuildTime.isEmpty()
                ? OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                : appBuildTime);
        data.put("git_commit", appGitCommit);
        data.put("sso_protocol", ssoProtocolVersion);
        // V2.0.3 诊断扩展字段
        data.put("pid", ProcessHandle.current().pid());
        data.put("started_at", OffsetDateTime.now().minusSeconds(uptimeSec)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("uptime_sec", uptimeSec);
        data.put("heap_used_mb", heapUsed);
        data.put("heap_max_mb", heapMax);
        return ApiResponse.ok(data);
    }
}