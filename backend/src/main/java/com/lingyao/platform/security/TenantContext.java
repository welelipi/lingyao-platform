package com.lingyao.platform.security;

import com.lingyao.platform.entity.CompanyUser;
import com.lingyao.platform.repository.CompanyUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 租户上下文 — 公司超管视角的便捷读取
 *
 * 当前用户 = JwtAuthFilter.getCurrentUser() 返回的 CurrentUser
 * 包含：公司 ID、用户 ID、平台超管标记
 *
 * 公司超管角色（SUPER_ADMIN/OPERATOR/VIEWER）从 CompanyUser 表读取
 * 因为用户可以属于多个公司，且每个公司角色可能不同
 */
@Component
public class TenantContext {

    @Autowired
    private CompanyUserRepository companyUserRepo;

    /**
     * 获取当前用户在指定公司的角色
     */
    public CompanyUser.CompanyRole getRoleInCompany(Long userId, Long companyId) {
        Optional<CompanyUser> cu = companyUserRepo.findByCompanyIdAndUserId(companyId, userId);
        return cu.map(CompanyUser::getRole).orElse(null);
    }

    /**
     * 判断当前用户是否是某公司的公司超管（SUPER_ADMIN + ACTIVE）
     */
    public boolean isTenantAdmin(Long userId, Long companyId) {
        Optional<CompanyUser> cu = companyUserRepo.findByCompanyIdAndUserId(companyId, userId);
        if (cu.isEmpty()) return false;
        CompanyUser c = cu.get();
        return c.getRole() == CompanyUser.CompanyRole.SUPER_ADMIN
                && c.getStatus() == CompanyUser.Status.ACTIVE;
    }

    /**
     * 获取当前用户作为 SUPER_ADMIN 的第一个公司（用于登录后跳转定位）
     * 优先返回 ACTIVE 状态的公司超管关系
     */
    public Long findFirstTenantAdminCompany(Long userId) {
        List<CompanyUser> memberships = companyUserRepo.findByUserId(userId);
        return memberships.stream()
            .filter(c -> c.getRole() == CompanyUser.CompanyRole.SUPER_ADMIN
                      && c.getStatus() == CompanyUser.Status.ACTIVE)
            .map(CompanyUser::getCompanyId)
            .findFirst()
            .orElse(null);
    }

    /**
     * 获取当前用户所属公司（可能多个，返回第一个 ACTIVE）
     */
    public Long findFirstActiveCompany(Long userId) {
        List<CompanyUser> memberships = companyUserRepo.findByUserId(userId);
        return memberships.stream()
            .filter(c -> c.getStatus() == CompanyUser.Status.ACTIVE)
            .map(CompanyUser::getCompanyId)
            .findFirst()
            .orElse(null);
    }
}