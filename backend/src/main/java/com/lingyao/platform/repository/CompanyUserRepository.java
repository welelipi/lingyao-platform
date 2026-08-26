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
}
