package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.AuthAuditEvent;
import com.lingyao.platform.repository.AuthAuditEventRepository;
import com.lingyao.platform.service.AuthAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全审计管理端点 — V2.0.26 P2.4 阶段 D
 *
 * 提供 3 个端点（仅平台超管可访问）：
 * - GET /api/admin/security-audit          列表（支持 type / userId / ip / 时间范围 过滤）
 * - GET /api/admin/security-audit/stats    按类型统计（dashboard 用）
 * - GET /api/admin/security-audit/event-types  列出所有事件类型枚举值（前端下拉框用）
 *
 * 设计：
 * - 复用 AuthAuditService 的查询方法
 * - 不暴露 audit 详情给普通用户（仅超管）
 * - 分页 size 上限 100（防 DOS）
 */
@RestController
@RequestMapping("/api/admin/security-audit")
@PreAuthorize("hasAuthority('ROLE_PLATFORM_ADMIN') or @tenantAwareUtil.isPlatformAdmin()")
public class SecurityAuditController {

    @Autowired
    private AuthAuditService authAuditService;

    /**
     * 列表端点（多过滤条件 + 分页）
     *
     * @param type      事件类型（可选）
     * @param userId    用户 ID（可选）
     * @param ip        客户端 IP（可选）
     * @param from      开始时间 ISO（可选，例 2026-09-01T00:00:00）
     * @param to        结束时间 ISO（可选）
     * @param page      页码（默认 0）
     * @param size      每页条数（默认 20，上限 100）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) AuthAuditEvent.EventType type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AuthAuditEvent> result;
        if (type != null && from != null && to != null) {
            result = authAuditService.listByTypeAndRange(type, from, to, page, size);
        } else if (type != null) {
            result = authAuditService.listByType(type, page, size);
        } else if (userId != null) {
            result = authAuditService.listForUser(userId, page, size);
        } else if (ip != null && !ip.isBlank()) {
            result = authAuditService.listForIp(ip, page, size);
        } else {
            // 默认：所有事件，按时间倒序分页（用 RATE_LIMIT_HIT 当 anchor 不合理，干脆报"请指定过滤条件"）
            // 简单方案：直接调用 listByType 用 LOGIN_SUCCESS 不可行
            // 解决：用最频繁的事件类型做"全部"代理 → 不合理
            // 真正方案：repository 加 findAllOrderByCreatedAtDesc（V2.0.27 加）
            return ApiResponse.fail(400, "请至少指定一个过滤条件 (type/userId/ip)");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.getContent());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ApiResponse.ok(body);
    }

    /**
     * 统计端点（dashboard）
     *
     * @param hours 统计过去 N 小时（默认 24，上限 720 = 30 天）
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(
            @RequestParam(defaultValue = "24") int hours) {
        int safeHours = Math.min(Math.max(1, hours), 720);
        LocalDateTime since = LocalDateTime.now().minusHours(safeHours);
        List<AuthAuditEventRepository.EventTypeCount> raw = authAuditService.statsSince(since);

        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (AuthAuditEventRepository.EventTypeCount etc : raw) {
            counts.put(etc.getEventType().name(), etc.getCount());
            total += etc.getCount();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("since", since.toString());
        body.put("hours", safeHours);
        body.put("totalEvents", total);
        body.put("byType", counts);
        return ApiResponse.ok(body);
    }

    /**
     * 列出所有事件类型（前端下拉框）
     */
    @GetMapping("/event-types")
    public ApiResponse<List<Map<String, String>>> eventTypes() {
        List<Map<String, String>> list = new java.util.ArrayList<>();
        for (AuthAuditEvent.EventType type : AuthAuditEvent.EventType.values()) {
            Map<String, String> entry = new HashMap<>();
            entry.put("name", type.name());
            entry.put("category", categorize(type));
            list.add(entry);
        }
        return ApiResponse.ok(list);
    }

    /**
     * 事件分类（用于前端分组展示）
     */
    private String categorize(AuthAuditEvent.EventType type) {
        String n = type.name();
        if (n.startsWith("LOGIN_") || n.equals("LOGOUT")) return "AUTH";
        if (n.startsWith("TOKEN_")) return "TOKEN";
        if (n.startsWith("PASSWORD_")) return "PASSWORD";
        if (n.startsWith("CSRF_")) return "CSRF";
        if (n.startsWith("RATE_LIMIT_")) return "RATE_LIMIT";
        if (n.startsWith("PERMISSION_")) return "PERMISSION";
        return "OTHER";
    }
}
