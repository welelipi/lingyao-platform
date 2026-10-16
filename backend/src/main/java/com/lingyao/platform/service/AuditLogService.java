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

    /**
     * 按当前登录人所属公司查询审计日志。
     *
     * V2.0.78.27 安全（L-02）：fail-open → fail-closed。
     *
     * 原实现：
     *   if (cu == null || cu.getCompanyId() == null) {
     *       return repo.findAllByOrderByCreatedAtDesc(pageable);   // ← 退化全表，零过滤
     *   }
     *
     * 危害放大点：cu == null / companyId == null 并不是「只有平台超管才会命中」的
     * 罕见态 —— 未绑定公司的普通员工 token 就落在这一分支，结果是被静默放大成
     * 「读全表」（含 actorUsername / companyId / ipAddress）。实测普通员工 token
     * 读到 109 条跨公司记录。
     *
     * 现在改为 fail-closed：
     * - cu == null                → 空页（没有登录上下文就没有可见范围，绝不退化为全表）
     * - companyId == null         → 仅平台超管可见全表；其余一律空页
     * - companyId != null         → 原有按公司过滤行为不变
     *
     * 注：超管判定字段是 CurrentUser#isPlatformAdmin（Lombok 为 boolean platformAdmin
     * 生成），不是 isSuper()/getRole()。
     */
    public Page<CompanyAuditLog> listForCurrentCompany(int page, int size) {
        Pageable pageable = PageRequest.of(
            Math.max(0, page),
            Math.min(Math.max(1, size), 100)
        );
        CurrentUser cu = CurrentUser.get();
        // fail-closed：无登录上下文 → 无可见范围
        if (cu == null) {
            return Page.empty(pageable);
        }
        if (cu.getCompanyId() == null) {
            // 无公司归属：只有平台超管才有权看全表，普通员工不得退化为全表
            if (!cu.isPlatformAdmin()) {
                return Page.empty(pageable);
            }
            return repo.findAllByOrderByCreatedAtDesc(pageable);
        }
        return repo.findByCompanyIdOrderByCreatedAtDesc(cu.getCompanyId(), pageable);
    }
}
