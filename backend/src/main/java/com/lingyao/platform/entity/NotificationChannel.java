package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 推送通道配置
 *
 * 支持 3 种通道：
 * - FEISHU：飞书群机器人 Webhook
 * - WECHAT_WORK：企业微信群机器人 Webhook
 * - WECHAT_MP：微信公众号模板消息（需 AppID/Secret）
 */
@Data
@Entity
@Table(name = "notification_channel", indexes = {
    @Index(name = "idx_nc_type_status", columnList = "channel_type,status")
})
@EntityListeners(AuditingEntityListener.class)
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 32)
    private ChannelType channelType;

    @Column(nullable = false, length = 64)
    private String name;

    /** Webhook URL（飞书/企微） */
    @Column(name = "webhook_url", length = 512)
    private String webhookUrl;

    /** 密钥（微信 AppSecret 等） */
    @Column(length = 256)
    private String secret;

    /** AppID（微信公众号） */
    @Column(name = "app_id", length = 64)
    private String appId;

    /**
     * 接收者 OpenID 列表（JSON，如 ["user1","user2"]）
     *
     * ⚠️ 不要加 @Lob —— Hibernate 6 + PostgreSQL 上 @Lob String 默认映射成 OID 大对象，
     *    即使 columnDefinition="TEXT" 覆盖了 DDL，JDBC bind 时仍走 OID 协议，
     *    INSERT JSON 字符串时会报 "Bad value for type long"。
     */
    @Column(name = "target_users", columnDefinition = "TEXT")
    private String targetUsers;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.INACTIVE;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(length = 256)
    private String remark;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum ChannelType { FEISHU, WECHAT_WORK, WECHAT_MP }
    public enum Status { ACTIVE, INACTIVE }
}
