package com.lingyao.platform.repository;

import com.lingyao.platform.entity.AuthAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AuthAuditEvent Repository — V2.0.26 P2.4 阶段 A
 *
 * 提供 4 类查询：
 * 1) 按事件类型（最近 N 条，用于安全监控仪表盘）
 * 2) 按用户（追溯某用户的安全事件）
 * 3) 按 IP（排查可疑 IP）
 * 4) 复合条件 + 分页（管理后台列表）
 *
 * 统计：按 event_type 聚合（dashboard 用）
 */
public interface AuthAuditEventRepository extends JpaRepository<AuthAuditEvent, Long> {

    /**
     * 按事件类型分页查询（倒序，最新在前）
     */
    Page<AuthAuditEvent> findByEventTypeOrderByCreatedAtDesc(
            AuthAuditEvent.EventType eventType, Pageable pageable);

    /**
     * 按用户 ID 分页查询（倒序）
     */
    Page<AuthAuditEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * 按 IP 分页查询（倒序）
     */
    Page<AuthAuditEvent> findByIpAddressOrderByCreatedAtDesc(String ipAddress, Pageable pageable);

    /**
     * 按时间范围 + 类型分页查询
     */
    Page<AuthAuditEvent> findByEventTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
            AuthAuditEvent.EventType eventType, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * 按类型统计数量（dashboard 用）
     * 返回 [eventType, count] 元组列表
     */
    @Query("SELECT e.eventType AS eventType, COUNT(e) AS count " +
           "FROM AuthAuditEvent e " +
           "WHERE e.createdAt >= :since " +
           "GROUP BY e.eventType " +
           "ORDER BY COUNT(e) DESC")
    List<EventTypeCount> countByEventTypeSince(@Param("since") LocalDateTime since);

    /**
     * 统计接口的投影
     */
    interface EventTypeCount {
        AuthAuditEvent.EventType getEventType();
        Long getCount();
    }
}
