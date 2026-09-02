package com.lingyao.platform.service;

import com.lingyao.platform.entity.AuthAuditEvent;
import com.lingyao.platform.repository.AuthAuditEventRepository;
import com.lingyao.platform.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 认证安全审计服务 — V2.0.26 P2.4 阶段 A/B
 *
 * 与 AuditLogService 的差异：
 * - AuditLogService：业务操作审计（公司层），与 company_audit_log 表对应
 * - AuthAuditService：安全事件审计（系统层），与 auth_audit_event 表对应
 *
 * 设计原则：
 * - 同步写入（参考 Bug-17 修复经验：@Async 会丢 ThreadLocal，AuditLogService 已用同步）
 * - 写入失败不影响主业务（try/catch 包裹，logger.error 不抛出）
 * - 主调用方：AuthService / CsrfFilter / RateLimitFilter / JwtAuthFilter
 *
 * 字段填充策略：
 * - 优先读 CurrentUser（来自 JwtAuthFilter 已设置）
 * - 没 CurrentUser 时（如登录前 / CSRF 拒绝）→ 显式传 userId / username
 * - metadata 用 JSON 字符串（手工拼接，规避 Jackson 依赖）
 */
@Service
public class AuthAuditService {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditService.class);

    @Autowired
    private AuthAuditEventRepository repo;

    // ══════════════════════════════════════════════════════════════════
    // 记录方法（主入口）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 记录安全事件（最常用入口）
     *
     * @param eventType     事件类型
     * @param success       是否成功
     * @param userId        关联用户 ID（可空，登录失败/CSRF 拒绝时无）
     * @param username      关联用户名（可空）
     * @param ip            客户端 IP
     * @param userAgent     UA（可空，会截断到 512 字符）
     * @param requestPath   请求路径
     * @param requestMethod 请求方法
     * @param errorCode     错误码（成功时为 null）
     * @param errorMessage  错误消息（成功时为 null，会脱敏）
     * @param metadata      扩展元数据 Map → 序列化为 JSON（可空）
     */
    @Transactional
    public void recordEvent(AuthAuditEvent.EventType eventType,
                            boolean success,
                            Long userId,
                            String username,
                            String ip,
                            String userAgent,
                            String requestPath,
                            String requestMethod,
                            String errorCode,
                            String errorMessage,
                            Map<String, Object> metadata) {
        try {
            AuthAuditEvent event = new AuthAuditEvent();
            event.setEventType(eventType);
            event.setSuccess(success);
            event.setUserId(userId);
            event.setUsername(username);
            event.setIpAddress(ip);
            event.setUserAgent(truncate(userAgent, 512));
            event.setRequestPath(truncate(requestPath, 256));
            event.setRequestMethod(requestMethod);
            event.setErrorCode(errorCode);
            event.setErrorMessage(truncate(errorMessage, 512));
            event.setMetadata(toJson(metadata));

            // 优先从 CurrentUser 拿 companyId（多租户隔离）
            CurrentUser cu = CurrentUser.get();
            if (cu != null) {
                if (event.getCompanyId() == null && cu.getCompanyId() != null) {
                    event.setCompanyId(cu.getCompanyId());
                }
                // 优先用 CurrentUser 的 userId 覆盖（更准确）
                if (cu.getUserId() != null) {
                    event.setUserId(cu.getUserId());
                }
                if (cu.getUsername() != null) {
                    event.setUsername(cu.getUsername());
                }
            }

            repo.save(event);
        } catch (Exception e) {
            // 审计失败不影响主业务
            log.error("AuthAudit 写入失败: eventType={} userId={} path={}",
                    eventType, userId, requestPath, e);
        }
    }

    /**
     * 简化版本：成功事件（无需 errorCode/Message）
     */
    public void recordSuccess(AuthAuditEvent.EventType type, Long userId, String username,
                              String ip, String path, String method) {
        recordEvent(type, true, userId, username, ip, null, path, method, null, null, null);
    }

    /**
     * 简化版本：失败事件（需 errorCode/Message）
     */
    public void recordFailure(AuthAuditEvent.EventType type, Long userId, String username,
                              String ip, String path, String method,
                              String errorCode, String errorMessage) {
        recordEvent(type, false, userId, username, ip, null, path, method,
                errorCode, errorMessage, null);
    }

    /**
     * 简化版本：失败事件 + metadata（限流/CSRF 等需要存额外信息）
     */
    public void recordFailureWithMeta(AuthAuditEvent.EventType type, Long userId, String username,
                                      String ip, String path, String method,
                                      String errorCode, String errorMessage,
                                      Map<String, Object> metadata) {
        recordEvent(type, false, userId, username, ip, null, path, method,
                errorCode, errorMessage, metadata);
    }

    // ══════════════════════════════════════════════════════════════════
    // 查询方法（admin 端点用）
    // ══════════════════════════════════════════════════════════════════

    public Page<AuthAuditEvent> listByType(AuthAuditEvent.EventType type, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return repo.findByEventTypeOrderByCreatedAtDesc(type, pageable);
    }

    public Page<AuthAuditEvent> listForUser(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<AuthAuditEvent> listForIp(String ip, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return repo.findByIpAddressOrderByCreatedAtDesc(ip, pageable);
    }

    public Page<AuthAuditEvent> listByTypeAndRange(AuthAuditEvent.EventType type,
                                                    LocalDateTime from, LocalDateTime to,
                                                    int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return repo.findByEventTypeAndCreatedAtBetweenOrderByCreatedAtDesc(type, from, to, pageable);
    }

    /**
     * Dashboard 统计：过去 N 小时内各事件类型计数
     */
    public List<AuthAuditEventRepository.EventTypeCount> statsSince(LocalDateTime since) {
        return repo.countByEventTypeSince(since);
    }

    // ══════════════════════════════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════════════════════════════

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    /**
     * Map → JSON 字符串（轻量手工拼接，避免引入 Jackson 依赖）
     *
     * 注：只支持 String/Number/Boolean/null 类型的 value（够用即可）
     */
    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append("\"").append(escapeJson(v.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
