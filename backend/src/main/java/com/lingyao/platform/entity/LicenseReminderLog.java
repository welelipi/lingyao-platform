package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 许可证过期提醒日志 — V2.0.10
 *
 * 作用：
 * 1. 防止重复提醒：scheduler 检查"今天是否已发过该 reminder_type 给该 company"
 * 2. 审计追溯：管理员可查看到期提醒历史
 * 3. 故障排查：success/error_message 字段记录推送失败原因
 *
 * 字段：
 * - company_id: 公司 ID
 * - reminder_type: EXPIRING（即将到期）/ EXPIRED（已过期）
 * - notice_days: 提前天数（30/15/7/1，EXPIRED 时为 NULL）
 * - sent_to: 接收人列表（邮箱/企微 id 逗号分隔）
 * - channel: 推送通道（WECHAT_WORK / EMAIL / IN_APP）
 * - sent_at: 发送时间
 * - success: 是否成功
 * - error_message: 失败时的错误信息
 */
@Data
@Entity
@Table(name = "license_reminder_log", indexes = {
        @Index(name = "idx_license_reminder_company", columnList = "company_id"),
        @Index(name = "idx_license_reminder_type", columnList = "reminder_type"),
        @Index(name = "idx_license_reminder_sent_at", columnList = "sent_at")
})
@EntityListeners(AuditingEntityListener.class)
public class LicenseReminderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", length = 16, nullable = false)
    private ReminderType reminderType;

    @Column(name = "notice_days")
    private Integer noticeDays;

    @Column(name = "sent_to", length = 512)
    private String sentTo;

    @Column(name = "channel", length = 32)
    private String channel;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    @Column(nullable = false)
    private Boolean success = true;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum ReminderType { EXPIRING, EXPIRED }
}