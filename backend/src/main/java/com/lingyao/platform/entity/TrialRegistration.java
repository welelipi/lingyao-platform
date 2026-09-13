package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Trial 注册记录 · V2.0.78.0（主站 D-5 Day 1）
 *
 * 主站侧 7 项拍板：5A+1B+1认可（2026-09-13 14:25 主人拍板）
 * - ① 报名公开性 → A 纯公开
 * - ② 试用用户名 → A 手机号
 * - ③ 首巡检邮件触发 → B 主站轮询
 * - ④ 周报/月报发送方 → A 主站代发
 * - ⑤ 健康度分诊计算 → A 主站算
 * - ⑥ 延长试用审批 → B 商务+超管
 * - ⑦ 冻结 15 天 / 删除 T+60 → 认可
 *
 * 关联文档：/tmp/MainSite_D5_alignment_2026-09-13.md
 */
@Data
@Entity
@Table(name = "trial_registration", indexes = {
        @Index(name = "idx_tr_phone", columnList = "phone"),
        @Index(name = "idx_tr_status", columnList = "status"),
        @Index(name = "idx_tr_company_id", columnList = "company_id"),
        @Index(name = "idx_tr_trial_expires_at", columnList = "trial_expires_at"),
        @Index(name = "idx_tr_created_at", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class TrialRegistration {

    /** 状态机：PENDING → ACTIVATED → EXPIRED → FROZEN → DELETED */
    public enum Status {
        /** 待激活（注册后 5 分钟内未点击开通链接） */
        PENDING,
        /** 已激活（trial 公司已创建 + 超管已开通 + 邮件已发送） */
        ACTIVATED,
        /** 已过期（trial_expires_at 已过） */
        EXPIRED,
        /** 已冻结（过期后 15 天内） */
        FROZEN,
        /** 已删除（冻结后 60 天 · 物理删除） */
        DELETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ② 手机号（试用用户名主键） */
    @Column(nullable = false, length = 32)
    private String phone;

    /** 邮箱（开通链接 + 周报发送目标） */
    @Column(nullable = false, length = 128)
    private String email;

    /** 公司名称 */
    @Column(nullable = false, length = 128)
    private String companyName;

    /** 来源渠道（官网首页/直播扫码/朋友介绍/广告/其他） */
    @Column(length = 64)
    private String source;

    /** 状态（默认 PENDING） */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.PENDING;

    /** 关联 trial 公司 ID（创建后回填） */
    @Column(name = "company_id")
    private Long companyId;

    /** trial 试用期到期时间（激活时 = NOW() + 7 天） */
    @Column(name = "trial_expires_at")
    private LocalDateTime trialExpiresAt;

    /** 首次巡检时间（主站 cron 触发后回填） */
    @Column(name = "first_inspection_at")
    private LocalDateTime firstInspectionAt;

    /** 健康度分（0~100） */
    @Column(name = "health_score")
    private Integer healthScore;

    /** IP 地址（限流 + 防滥用） */
    @Column(length = 64)
    private String ipAddress;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
