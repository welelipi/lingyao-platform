package com.lingyao.platform.service;

import com.lingyao.platform.entity.CompanyAuditLog;
import com.lingyao.platform.entity.CompanyUser;
import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.repository.CompanyAuditLogRepository;
import com.lingyao.platform.repository.CompanyUserRepository;
import com.lingyao.platform.repository.SysUserRepository;
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

    @Autowired
    private CompanyUserRepository companyUserRepo;

    @Autowired
    private SysUserRepository sysUserRepo;

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

    /**
     * V2.0.63：显式主体回填重载 —— 登录类事件专用
     *
     * 背景（V2.0.62 发现的既有缺陷）：登录发生时还没有 JWT 上下文，
     * CurrentUser.get() 为 null → companyId=null → 登录记录不属任何公司，
     * 公司超管在审计日志 tab 里永远看不到本公司的登录记录。
     *
     * 归属策略（按 userId 反查）：
     * - company_user 有记录 → companyId = 第一条（一人可属多公司时取第一个）
     * - username 回填：user != null 时直接取，否则按 userId 查 sys_user
     * - 平台超管/无公司归属 → 保持 null（不强行归属，平台侧安全审计 tab 仍可见）
     */
    @org.springframework.transaction.annotation.Transactional
    public void recordWithSubject(String action, String resourceType, Long subjectUserId,
                                  String subjectUsername, String summary) {
        try {
            CompanyAuditLog log = new CompanyAuditLog();
            log.setAction(action);
            log.setResourceType(resourceType);
            log.setResourceId(subjectUserId == null ? "-1" : String.valueOf(subjectUserId));
            log.setSummary(SanitizeUtil.escapeHtml(summary));

            // 1) 公司归属：按 userId 反查 company_user
            if (subjectUserId != null && subjectUserId > 0) {
                log.setActorUserId(subjectUserId);
                companyUserRepo.findByUserId(subjectUserId).stream().findFirst()
                        .ifPresent(cu -> log.setCompanyId(cu.getCompanyId()));
            }

            // 2) 操作人用户名：优先显式传入，否则查库
            if (subjectUsername != null && !subjectUsername.isBlank()) {
                log.setActorUsername(subjectUsername);
            } else if (subjectUserId != null && subjectUserId > 0) {
                sysUserRepo.findById(subjectUserId)
                        .map(SysUser::getUsername)
                        .ifPresent(log::setActorUsername);
            }

            repo.save(log);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuditLogService.class)
                    .error("审计日志写入失败: action=" + action + " subjectUserId=" + subjectUserId, e);
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
