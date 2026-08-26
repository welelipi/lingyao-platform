package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 产品目录（4 大产品线）
 */
@Data
@Entity
@Table(name = "product", indexes = {
    @Index(name = "idx_product_code", columnList = "code", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String code;  // GEO / HPD / AIDD / POR

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(length = 16)
    private String icon;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Status status = Status.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Status { ACTIVE, DEPRECATED, COMING_SOON }
}
