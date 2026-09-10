package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * V2.0.69 D：受信任设备（30 天信任期内免邮箱码验证）
 *
 * V2.0.77.6 升级：UA 容易因浏览器自动升级而漂移（如 Chrome 152.0 → 152.1），
 * 单 UA 指纹会让主人"30 天内反复登录被要求邮箱码"。现增加 IP 段（IPv4 /24 或 IPv6 /64），
 * 信任判定改为「UA 指纹命中 OR (user_id + IP 段命中)」——同办公网内换设备/换 UA 也不触发。
 */
@Data
@Entity
@Table(name = "device_trust", indexes = {
    @Index(name = "idx_devtrust_user_fp", columnList = "userId,deviceFp", unique = true),
    @Index(name = "idx_devtrust_expires", columnList = "expiresAt"),
    @Index(name = "idx_devtrust_user_ipc", columnList = "userId,ipClassC")
})
@EntityListeners(AuditingEntityListener.class)
public class DeviceTrust {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 设备指纹：SHA-256(userId + "|" + UA + "|" + ipClassC) 前 32 hex（V2.0.77.6 加 IP 段） */
    @Column(name = "device_fp", nullable = false, length = 32)
    private String deviceFp;

    /** 首次信任时的 IP（仅审计参考，不参与匹配） */
    @Column(length = 64)
    private String trustedIp;

    /** V2.0.77.6：IP 段（IPv4 /24 或 IPv6 /64），用于"同 IP 段任一指纹命中即信任" */
    @Column(name = "ip_class_c", length = 64)
    private String ipClassC;

    /** 首次信任时的 UA 摘要（审计展示用） */
    @Column(length = 128)
    private String userAgentSummary;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
