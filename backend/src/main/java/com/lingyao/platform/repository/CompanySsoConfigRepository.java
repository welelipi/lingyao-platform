package com.lingyao.platform.repository;

import com.lingyao.platform.entity.CompanySsoConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanySsoConfigRepository extends JpaRepository<CompanySsoConfig, Long> {

    List<CompanySsoConfig> findByCompanyId(Long companyId);

    List<CompanySsoConfig> findByCompanyIdAndEnabledTrue(Long companyId);

    Optional<CompanySsoConfig> findByCompanyIdAndProvider(Long companyId, CompanySsoConfig.Provider provider);

    boolean existsByCompanyIdAndProvider(Long companyId, CompanySsoConfig.Provider provider);

    void deleteByCompanyIdAndProvider(Long companyId, CompanySsoConfig.Provider provider);
}