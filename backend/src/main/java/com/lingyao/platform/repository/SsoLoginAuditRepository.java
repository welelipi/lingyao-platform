package com.lingyao.platform.repository;

import com.lingyao.platform.entity.SsoLoginAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SsoLoginAuditRepository extends JpaRepository<SsoLoginAudit, Long> {

    Page<SsoLoginAudit> findByCompanyIdOrderByCreatedAtDesc(Long companyId, Pageable pageable);

    Page<SsoLoginAudit> findByCompanyIdAndProviderOrderByCreatedAtDesc(
        Long companyId, com.lingyao.platform.entity.CompanySsoConfig.Provider provider, Pageable pageable);
}