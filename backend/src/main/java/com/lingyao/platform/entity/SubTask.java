package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 子任务配置（产品子任务接入点）
 *
 * 每个产品可注册一个子任务：
 * - GEO 子任务（独立服务/DB）
 * - 医院潜力预测子任务
 * - AIDD 子任务
 * - 协作智能体子任务
 *
 * 主框架通过此表路由请求到子任务
 */
@Data
@Entity
@Table(name = "sub_task", indexes = {
    @Index(name = "idx_st_product", columnList = "product_id"),
    @Index(name = "idx_st_code", columnList = "task_code", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class SubTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "task_name", nullable = false, length = 64)
    private String taskName;

    @Column(name = "task_code", nullable = false, length = 64)
    private String taskCode;

    /** 主框架中的入口路径（用于前端菜单跳转） */
    @Column(name = "entry_path", length = 128)
    private String entryPath;

    /** 子任务的 API 前缀（主框架代理/转发） */
    @Column(name = "api_prefix", length = 128)
    private String apiPrefix;

    /** 子任务的健康检查地址 */
    @Column(name = "health_url", length = 256)
    private String healthUrl;

    /** 子任务实际部署地址 */
    @Column(name = "base_url", length = 256)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.REGISTERED;

    /**
     * 配置 JSON（如 {"apiKey":"xxx","pollInterval":300}）
     *
     * ⚠️ 不要加 @Lob —— Hibernate 6 + PostgreSQL 上 @Lob String 默认映射成 OID 大对象，
     *    即使 columnDefinition="TEXT" 覆盖了 DDL，JDBC bind 时仍走 OID 协议，
     *    INSERT JSON 字符串时会报 "Bad value for type long"。
     */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(length = 512)
    private String description;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Status { REGISTERED, ACTIVE, MAINTENANCE, OFFLINE, COMING_SOON }
}
