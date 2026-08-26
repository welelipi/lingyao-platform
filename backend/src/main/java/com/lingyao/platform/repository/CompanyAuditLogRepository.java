package com.lingyao.platform.repository;

import com.lingyao.platform.entity.CompanyAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyAuditLogRepository extends JpaRepository<CompanyAuditLog, Long> {
    Page<CompanyAuditLog> findByCompanyIdOrderByCreatedAtDesc(Long companyId, Pageable pageable);
    Page<CompanyAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
