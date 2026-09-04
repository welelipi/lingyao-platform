package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * V2.0.69 D：受信任设备（30 天信任期内免邮箱码验证）
 *
 * 识别口径：SHA-256(userId + "|" + User-Agent) 前 32 位十六进制
 * —— UA 精度有限（同机同浏览器同指纹），但作为「低摩擦第二因子」足够：
 *   攻击者换机器/换浏览器即指纹变化 → 触发邮箱码验证；拿到密码 + 同 UA 的场景由其他层兜底
 */
@Data
@Entity
@Table(name = "device_trust", indexes = {
    @Index(name = "idx_devtrust_user_fp", columnList = "userId,deviceFp", unique = true),
    @Index(name = "idx_devtrust_expires", columnList = "expiresAt")
})
@EntityListeners(AuditingEntityListener.class)
public class DeviceTrust {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 设备指纹：SHA-256(userId|UA) 前 32 hex */
    @Column(name = "device_fp", nullable = false, length = 32)
    private String deviceFp;

    /** 首次信任时的 IP（仅审计参考，不参与匹配） */
    @Column(length = 64)
    private String trustedIp;

    /** 首次信任时的 UA 摘要（审计展示用） */
    @Column(length = 128)
    private String userAgentSummary;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
