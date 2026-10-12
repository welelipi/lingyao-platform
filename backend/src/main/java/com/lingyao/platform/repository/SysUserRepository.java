package com.lingyao.platform.repository;

import com.lingyao.platform.entity.SysUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsername(String username);
    Optional<SysUser> findByEmail(String email);

    /**
     * V2.0.78.18：邮箱字段历史上无唯一约束，可能出现多账号共用同一邮箱
     * （staging 实锤：admin 与测试账号 15510855337 共用 188407408@qq.com）。
     * 单结果 findByEmail 遇重复数据抛 NonUniqueResultException → forgot-password 500。
     * 此查询返回全部匹配，由调用方按「平台管理员 > ACTIVE > id 小」择一。
     */
    List<SysUser> findAllByEmail(String email);
    List<SysUser> findByIsPlatformAdminTrue();
    boolean existsByUsername(String username);

    /**
     * V2.0.46：平台超管后台「用户管理」只管理「平台超管 + 公司超管」，
     * 公司普通用户（OPERATOR/VIEWER）不在此出现 —— 普通用户由公司超管在子平台内管理。
     */
    @Query("""
        SELECT DISTINCT u FROM SysUser u
        WHERE u.isPlatformAdmin = true
           OR EXISTS (
             SELECT cu FROM CompanyUser cu
             WHERE cu.userId = u.id
               AND cu.role = com.lingyao.platform.entity.CompanyUser.CompanyRole.SUPER_ADMIN
               AND cu.status = com.lingyao.platform.entity.CompanyUser.Status.ACTIVE
           )
        ORDER BY u.id DESC
        """)
    Page<SysUser> findPlatformAndTenantAdmins(Pageable pageable);

    /** V2.0.46：同口径计数（KPI 卡片「用户总数」用） */
    @Query("""
        SELECT COUNT(DISTINCT u.id) FROM SysUser u
        WHERE u.isPlatformAdmin = true
           OR EXISTS (
             SELECT cu FROM CompanyUser cu
             WHERE cu.userId = u.id
               AND cu.role = com.lingyao.platform.entity.CompanyUser.CompanyRole.SUPER_ADMIN
               AND cu.status = com.lingyao.platform.entity.CompanyUser.Status.ACTIVE
           )
        """)
    long countPlatformAndTenantAdmins();
}
