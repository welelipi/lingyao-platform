package com.lingyao.platform.repository;

import com.lingyao.platform.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByCode(String code);
    List<Company> findByStatus(Company.CompanyStatus status);

    /**
     * V2.0.10：查找 ACTIVE 且 licenseEnd 落在 [start, end] 区间内的公司
     * 用于：过期前 30/15/7/1 天的提醒
     */
    List<Company> findByStatusAndLicenseEndBetween(
            Company.CompanyStatus status,
            LocalDateTime start,
            LocalDateTime end);

    /**
     * V2.0.10：查找所有 licenseEnd 已经早于 cutoffTime 的 ACTIVE 公司
     * 用于：每日 23:00 自动 ACTIVE → EXPIRED
     */
    List<Company> findByStatusAndLicenseEndBefore(
            Company.CompanyStatus status,
            LocalDateTime cutoffTime);
}
