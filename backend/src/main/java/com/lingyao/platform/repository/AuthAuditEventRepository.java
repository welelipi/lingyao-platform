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

    // ═══════════════════════ V2.0.42 审计归档 + 告警 ═══════════════════════

    /** 归档：取 N 天前的全部事件（分批搬运用） */
    List<AuthAuditEvent> findByCreatedAtBefore(LocalDateTime cutoff);

    /** 删除已归档事件（归档 job 第二步） */
    long deleteByCreatedAtBefore(LocalDateTime cutoff);

    /** 告警扫描：最近 N 分钟内某类型失败事件（LOGIN_FAIL/LOCKED 等暴增检测） */
    long countByEventTypeAndCreatedAtAfterAndSuccessFalse(
            AuthAuditEvent.EventType eventType, LocalDateTime since);

    /** 告警扫描：最近 N 分钟内 TOKEN_REFRESH_REPLAY（重放攻击检测，不看 success） */
    long countByEventTypeAndCreatedAtAfter(
            AuthAuditEvent.EventType eventType, LocalDateTime since);

    /** V2.0.61：近 N 时间失败事件总数（跨类型，KPI 用） */
    long countByCreatedAtAfterAndSuccessFalse(LocalDateTime since);

    // ═══════════════════════ V2.0.61 安全审计可视化 ═══════════════════════

    /**
     * 管理后台复合筛选查询（全部条件可空，null = 不筛选）
     * 索引覆盖：idx_auth_audit_type_created / idx_auth_audit_user_created / idx_auth_audit_created
     * 注意：CONCAT 必须显式 CAST(:username AS string)，否则 PG 在 null 参数时按 bytea 处理报 lower(bytea) 不存在
     */
    @Query("""
           SELECT e FROM AuthAuditEvent e
           WHERE (:eventType IS NULL OR e.eventType = :eventType)
             AND (CAST(:username AS string) IS NULL OR LOWER(e.username) LIKE LOWER(CONCAT('%', CAST(:username AS string), '%')))
             AND (:success IS NULL OR e.success = :success)
             AND (:companyId IS NULL OR e.companyId = :companyId)
           ORDER BY e.createdAt DESC
           """)
    Page<AuthAuditEvent> findByFilters(
            @Param("eventType") AuthAuditEvent.EventType eventType,
            @Param("username") String username,
            @Param("success") Boolean success,
            @Param("companyId") Long companyId,
            Pageable pageable);
}
