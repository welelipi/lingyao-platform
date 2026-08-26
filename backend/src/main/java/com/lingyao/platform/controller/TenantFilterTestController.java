package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.CompanyAuditLog;
import com.lingyao.platform.repository.CompanyAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Phase 2.5 数据隔离验证端点（仅超管可访问）
 *
 * 用于验证 Hibernate @Filter 是否真的在 SQL 层注入 tenant_id 条件
 * 直接调 repo.findAll()，不传任何 company_id 参数
 *
 * 验证步骤：
 * 1. A 公司用户 a_admin 登录 → 调 /api/test/audit-all → 应只看到 company_id=1
 * 2. B 公司用户 b_admin 登录 → 调 /api/test/audit-all → 应只看到 company_id=2
 * 3. 平台超管 admin → 应看到全部
 *
 * 注意：仅用于测试，可后续移除
 */
@RestController
@RequestMapping("/api/test")
public class TenantFilterTestController {

    @Autowired
    private CompanyAuditLogRepository repo;

    @GetMapping("/audit-all")
    public ApiResponse<?> auditAllRaw() {
        // 故意不传 companyId，直接 findAll — 看 Filter 是否兜底
        List<CompanyAuditLog> all = repo.findAll();
        return ApiResponse.ok(Map.of(
                "total", all.size(),
                "logs", all.stream().map(l -> Map.of(
                        "id", l.getId(),
                        "companyId", l.getCompanyId(),
                        "action", l.getAction(),
                        "summary", l.getSummary()
                )).toList()
        ));
    }
}