package com.lingyao.platform.service;

import com.lingyao.platform.entity.CompanyAuditLog;
import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.repository.CompanyAuditLogRepository;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.util.SanitizeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务 — Bug-17 修复
 */
@Service
public class AuditLogService {

    @Autowired
    private CompanyAuditLogRepository repo;

    /**
     * Bug-fix: 移除 @Async 注解
     * 原因：@Async 切换线程后 CurrentUser ThreadLocal 丢失，
     * 导致审计日志 companyId / actorUserId 全部为 null，列表查询时过滤掉。
     * 同步写库损耗约 1-3ms，可接受。
     */
    @org.springframework.transaction.annotation.Transactional
    public void record(String action, String resourceType, String resourceId, String summary) {
        try {
            CompanyAuditLog log = new CompanyAuditLog();
            log.setAction(action);
            log.setResourceType(resourceType);
            log.setResourceId(resourceId);
            log.setSummary(SanitizeUtil.escapeHtml(summary));

            CurrentUser cu = CurrentUser.get();
            if (cu != null) {
                log.setCompanyId(cu.getCompanyId());
                log.setActorUserId(cu.getUserId());
                log.setActorUsername(cu.getUsername());
            }

            repo.save(log);
        } catch (Exception e) {
            // 审计失败不影响主业务
            org.slf4j.LoggerFactory.getLogger(AuditLogService.class)
                    .error("审计日志写入失败: action=" + action + " resourceId=" + resourceId, e);
        }
    }

    public Page<CompanyAuditLog> listForCurrentCompany(int page, int size) {
        Pageable pageable = PageRequest.of(
            Math.max(0, page),
            Math.min(Math.max(1, size), 100)
        );
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getCompanyId() == null) {
            return repo.findAllByOrderByCreatedAtDesc(pageable);
        }
        return repo.findByCompanyIdOrderByCreatedAtDesc(cu.getCompanyId(), pageable);
    }
}
