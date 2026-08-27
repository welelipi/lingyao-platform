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
}