package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Trial 健康度分 · V2.0.78.1（主站 D-5 Day 2）
 *
 * 主站侧 7 项拍板：⑤ 健康度分诊计算 → A 主站算
 *
 * 计算规则（每日 02:00 cron 跑批）：
 * - login_count > 3 = +30
 * - inspection_count > 1 = +40
 * - last_active_at 在 3 天内 = +30
 * - 总分 0~100，70+ = 健康 / 40~69 = 关注 / <40 = 不活跃
 *
 * 关联文档：/tmp/MainSite_D5_alignment_2026-09-13.md §Day 2
 */
@Data
@Entity
@Table(name = "trial_health_score", indexes = {
        @Index(name = "idx_ths_registration", columnList = "registration_id"),
        @Index(name = "idx_ths_company", columnList = "company_id"),
        @Index(name = "idx_ths_band", columnList = "band"),
        @Index(name = "idx_ths_score_date", columnList = "score_date")
})
@EntityListeners(AuditingEntityListener.class)
public class TrialHealthScore {

    /** 健康度分桶 */
    public enum Band {
        /** 健康（70~100） */
        HEALTHY,
        /** 关注（40~69） */
        WATCH,
        /** 不活跃（<40） */
        INACTIVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** trial_registration.id（1:1） */
    @Column(name = "registration_id", nullable = false, unique = true)
    private Long registrationId;

    /** 关联 trial 公司 ID */
    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** 关联用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 健康度分（0~100） */
    @Column(nullable = false)
    private Integer score = 0;

    /** 分桶 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Band band = Band.INACTIVE;

    /** 计算日（每日 02:00 当天日期） */
    @Column(name = "score_date", nullable = false)
    private LocalDateTime scoreDate;

    /** 登录次数（来自 sys_user.登录审计聚合） */
    @Column(name = "login_count", nullable = false)
    private Integer loginCount = 0;

    /** 巡检次数（trial_registration.first_inspection_at 计算后的累计值） */
    @Column(name = "inspection_count", nullable = false)
    private Integer inspectionCount = 0;

    /** 最近活跃时间（来自 sys_user.last_active_at） */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /** 计算备注（调试/可解释性） */
    @Column(length = 256)
    private String remark;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
