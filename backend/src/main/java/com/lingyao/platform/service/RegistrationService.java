package com.lingyao.platform.service;

import com.lingyao.platform.dto.RegistrationRequest;
import com.lingyao.platform.entity.Registration;
import com.lingyao.platform.repository.RegistrationRepository;
import com.lingyao.platform.util.SanitizeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 报名服务 — Bug-04/05/06/07/08 修复
 */
@Service
public class RegistrationService {

    /**
     * Bug-19 修复：与 data.sql 中 4 个产品 code 对齐（大写简称）
     * ——与登录响应、AdminController、SubTaskController、InvitationController 保持一致
     */
    private static final Set<String> VALID_PRODUCTS = new HashSet<>(Arrays.asList(
            "GEO", "HPD", "AIDD", "POR"
    ));

    @Autowired
    private RegistrationRepository repo;

    @Autowired
    private AuditLogService auditLogService;

    public Registration create(RegistrationRequest req) {
        // Bug-08：产品 code 必须在合法枚举内
        for (String code : req.getInterestedProducts()) {
            if (!VALID_PRODUCTS.contains(code)) {
                throw new IllegalArgumentException("非法产品: " + code + "，合法值: " + VALID_PRODUCTS);
            }
        }

        // Bug-05：手机号/邮箱去重（30 天内）
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        if (repo.existsByPhoneAndCreatedAtAfter(req.getPhone(), since)) {
            throw new IllegalArgumentException("该手机号 30 天内已提交过报名，请勿重复提交");
        }
        if (repo.existsByEmailAndCreatedAtAfter(req.getEmail(), since)) {
            throw new IllegalArgumentException("该邮箱 30 天内已提交过报名，请勿重复提交");
        }

        Registration reg = new Registration();
        reg.setName(req.getName());
        reg.setCompany(req.getCompany());
        reg.setPosition(req.getPosition());
        reg.setPhone(req.getPhone());
        reg.setEmail(req.getEmail());
        reg.setInterestedProducts(String.join(",", req.getInterestedProducts()));
        reg.setCompanySize(req.getCompanySize());
        reg.setSource(req.getSource());
        // Bug-06/07：转义 HTML 防 XSS
        reg.setMessage(SanitizeUtil.escapeHtml(req.getMessage()));
        reg.setStatus(Registration.Status.PENDING);
        reg.setCreatedAt(LocalDateTime.now());
        reg.setUpdatedAt(LocalDateTime.now());

        Registration saved = repo.save(reg);

        auditLogService.record("REGISTRATION_CREATE", "REGISTRATION", String.valueOf(saved.getId()),
                "新报名: " + saved.getName() + "/" + saved.getCompany() + " 感兴趣: " + saved.getInterestedProducts());

        return saved;
    }

    public Page<Registration> list(String status, Pageable pageable) {
        if (status == null || status.isEmpty() || "ALL".equalsIgnoreCase(status)) {
            return repo.findAllByOrderByCreatedAtDesc(pageable);
        }
        return repo.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    public Registration updateStatus(Long id, String newStatus) {
        Registration reg = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在: " + id));

        // 状态流转校验
        Set<String> validTransitions = new HashSet<>(Arrays.asList(
                "PENDING->CONTACTED", "PENDING->CLOSED", "PENDING->QUALIFIED",
                "CONTACTED->QUALIFIED", "CONTACTED->CLOSED",
                "QUALIFIED->CLOSED"
        ));
        String transition = reg.getStatus() + "->" + newStatus;
        if (!validTransitions.contains(transition)) {
            throw new IllegalArgumentException("非法的状态流转: " + transition);
        }
        if (!Arrays.asList("PENDING", "CONTACTED", "QUALIFIED", "CLOSED").contains(newStatus)) {
            throw new IllegalArgumentException("非法的状态值: " + newStatus);
        }

        reg.setStatus(Registration.Status.valueOf(newStatus));
        reg.setUpdatedAt(LocalDateTime.now());
        Registration saved = repo.save(reg);

        auditLogService.record("REGISTRATION_STATUS", "REGISTRATION", String.valueOf(saved.getId()),
                "状态变更: " + transition);

        return saved;
    }

    public long countByStatus(String status) {
        return repo.countByStatus(status);
    }

    public Registration findById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在: " + id));
    }
}
