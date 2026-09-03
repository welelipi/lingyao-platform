package com.lingyao.platform.repository;

import com.lingyao.platform.entity.CompanyAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CompanyAuditLogRepository extends JpaRepository<CompanyAuditLog, Long> {
    Page<CompanyAuditLog> findByCompanyIdOrderByCreatedAtDesc(Long companyId, Pageable pageable);
    Page<CompanyAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // ═══════════════════════ V2.0.42 审计归档 ═══════════════════════
    List<CompanyAuditLog> findByCreatedAtBefore(LocalDateTime cutoff);
    long deleteByCreatedAtBefore(LocalDateTime cutoff);

    // ═══════════════════════ V2.0.62 审计日志可视化 ═══════════════════════
    Page<CompanyAuditLog> findByCompanyIdAndActionOrderByCreatedAtDesc(
            Long companyId, String action, Pageable pageable);
}
