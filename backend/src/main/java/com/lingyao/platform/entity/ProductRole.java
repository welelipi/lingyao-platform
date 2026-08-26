package com.lingyao.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 产品内角色定义（第三层权限）
 *
 * 每个产品可定义自己的角色树：super_admin / senior_operator / operator / viewer
 * 角色绑定权限列表（JSON 存储）
 */
@Data
@Entity
@Table(name = "product_role", uniqueConstraints = {
    @UniqueConstraint(name = "uk_pr_product_role", columnNames = {"product_id", "role_code"})
})
@EntityListeners(AuditingEntityListener.class)
public class ProductRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 64)
    private String roleName;

    /** 权限列表（JSON 字符串，如 ["*"] 或 ["read","write"]） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String permissions;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
