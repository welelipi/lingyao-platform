package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 报名/试用意向记录
 *
 * 主人需求：超管后台能看到所有报名信息，并推送到飞书/企微/微信
 */
@Data
@Entity
@Table(name = "registration", indexes = {
    @Index(name = "idx_reg_status", columnList = "status"),
    @Index(name = "idx_reg_phone", columnList = "phone"),
    @Index(name = "idx_reg_email", columnList = "email"),
    @Index(name = "idx_reg_created", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;  // 联系人姓名

    @Column(length = 128)
    private String company;  // 公司名称

    @Column(length = 64)
    private String position;  // 职位

    @Column(length = 32)
    private String phone;

    @Column(length = 128)
    private String email;

    /** 感兴趣的产品（逗号分隔的 product_code） */
    @Column(name = "interested_products", length = 256)
    private String interestedProducts;

    /** 期望员工规模 */
    @Column(name = "company_size", length = 32)
    private String companySize;

    /** 来源渠道：官网首页 / 朋友介绍 / 会议 / 广告 / 其他 */
    @Column(length = 64)
    private String source;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String message;  // 留言

    /** 状态：PENDING / CONTACTED / QUALIFIED / CLOSED */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.PENDING;

    /** 处理人 */
    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "process_remark", length = 512)
    private String processRemark;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Status { PENDING, CONTACTED, QUALIFIED, CLOSED }
}
