package com.lingyao.platform.repository;

import com.lingyao.platform.entity.CompanyUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyUserRepository extends JpaRepository<CompanyUser, Long> {
    List<CompanyUser> findByCompanyId(Long companyId);
    List<CompanyUser> findByUserId(Long userId);
    Optional<CompanyUser> findByCompanyIdAndUserId(Long companyId, Long userId);
    boolean existsByCompanyIdAndUserId(Long companyId, Long userId);
    long countByCompanyIdAndStatus(Long companyId, CompanyUser.Status status);

    /**
     * V2.0.10：查找某公司的所有"公司超管"（SUPER_ADMIN 角色）成员关系
     */
    List<CompanyUser> findByCompanyIdAndRole(Long companyId, CompanyUser.CompanyRole role);
}
