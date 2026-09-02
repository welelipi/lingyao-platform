package com.lingyao.platform.repository;

import com.lingyao.platform.entity.CompanyAuditLogArchive;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyAuditLogArchiveRepository extends JpaRepository<CompanyAuditLogArchive, Long> {
}
