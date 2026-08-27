package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 发布历史 — V2.0.5 R-7
 *
 * 记录每次「发布 staging」或「晋升生产」的完整过程：
 * - env          目标环境（STAGING / PROD）
 * - version      部署的 jar 版本
 * - deployed_by  触发人 username
 * - status       部署状态（RUNNING / SUCCESS / FAILED / CANCELLED）
 * - log          完整 stdout 输出（脱敏后）
 * - duration_sec 部署耗时
 *
 * 权限：仅 platform_admin 可读/写（Service/Controller 层强制校验）
 */
@Data
@Entity
@Table(name = "release_history", indexes = {
    @Index(name = "idx_release_env_started", columnList = "env,started_at"),
    @Index(name = "idx_release_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
public class ReleaseHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目标环境 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReleaseEnv env;

    /** 部署的版本号（如 "2.0.5"） */
    @Column(nullable = false, length = 64)
    private String version;

    /** jar 文件名（含扩展名） */
    @Column(name = "jar_filename", length = 128)
    private String jarFilename;

    /** 触发人 username */
    @Column(name = "deployed_by", nullable = false, length = 64)
    private String deployedBy;

    /** 触发人 userId */
    @Column(name = "deployed_by_id")
    private Long deployedById;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReleaseStatus status = ReleaseStatus.RUNNING;

    /** 部署脚本 stdout/stderr 全文（用于审计） */
    @Column(columnDefinition = "TEXT")
    private String log;

    /** 失败时的简短原因 */
    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 耗时（秒）— finishedAt - startedAt */
    @Column(name = "duration_sec")
    private Integer durationSec;

    /** Webhook 推送状态：SUCCESS / FAILED / SKIPPED（未配置） */
    @Column(name = "webhook_status", length = 32)
    private String webhookStatus;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum ReleaseEnv { STAGING, PROD }
    public enum ReleaseStatus { RUNNING, SUCCESS, FAILED, CANCELLED }
}