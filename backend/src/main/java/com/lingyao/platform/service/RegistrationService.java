package com.lingyao.platform.service;

import com.lingyao.platform.dto.RegistrationRequest;
import com.lingyao.platform.entity.Registration;
import com.lingyao.platform.repository.RegistrationRepository;
import com.lingyao.platform.util.SanitizeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * Bug-19 修复：与 data.sql 中产品 code 对齐（大写简称）
     * ——与登录响应、AdminController、SubTaskController、InvitationController 保持一致
     * V2.0.55：目录收敛 4 产品（POR 已下架、MEDPPT 上架）
     */
    private static final Set<String> VALID_PRODUCTS = new HashSet<>(Arrays.asList(
            "GEO", "HPD", "AIDD", "MEDPPT"
    ));

    @Autowired
    private RegistrationRepository repo;

    @Autowired
    private AuditLogService auditLogService;

    /**
     * V2.0.30：报名重复检测开关（主人 2026-09-02 09:59 指令：测试期暂时关闭）。
     * true = 恢复 30 天手机号/邮箱去重；false = 完全跳过（测试期默认）。
     * 恢复方式：application.yml 置 true 并重启，无需改代码。
     */
    @Value("${app.registration.duplicate-check-enabled:false}")
    private boolean duplicateCheckEnabled;

    public Registration create(RegistrationRequest req) {
        // Bug-08：产品 code 必须在合法枚举内
        for (String code : req.getInterestedProducts()) {
            if (!VALID_PRODUCTS.contains(code)) {
                throw new IllegalArgumentException("非法产品: " + code + "，合法值: " + VALID_PRODUCTS);
            }
        }

        // Bug-05：手机号/邮箱去重（30 天内）—— V2.0.30 测试期暂时关闭（开关见上方字段注释）
        if (duplicateCheckEnabled) {
            LocalDateTime since = LocalDateTime.now().minusDays(30);
            if (repo.existsByPhoneAndCreatedAtAfter(req.getPhone(), since)) {
                throw new IllegalArgumentException("该手机号 30 天内已提交过报名，请勿重复提交");
            }
            if (repo.existsByEmailAndCreatedAtAfter(req.getEmail(), since)) {
                throw new IllegalArgumentException("该邮箱 30 天内已提交过报名，请勿重复提交");
            }
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
        // Bug-20 修复：String → Status 枚举转换
        return repo.findByStatusOrderByCreatedAtDesc(Registration.Status.valueOf(status), pageable);
    }

    /**
     * V2.0.28 方案B：报名转开通（超管在组合创建公司+管理员成功后调用）。
     * PENDING/CONTACTED/QUALIFIED 均可转入 CONVERTED；CONVERTED/CLOSED 为终态不可再转。
     */
    @Transactional
    public Registration convertToClosed(Long id) {
        Registration reg = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在: " + id));
        String cur = reg.getStatus().name();
        if (!Arrays.asList("PENDING", "CONTACTED", "QUALIFIED").contains(cur)) {
            throw new IllegalArgumentException("当前状态 " + cur + " 不可转化（终态）");
        }
        reg.setStatus(Registration.Status.CONVERTED);
        reg.setUpdatedAt(LocalDateTime.now());
        Registration saved = repo.save(reg);
        auditLogService.record("REGISTRATION_CONVERT", "REGISTRATION", String.valueOf(saved.getId()),
                "报名转开通: " + cur + " → CONVERTED");
        return saved;
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
        // Bug-20 修复：String → Status 枚举转换（否则 Spring Data 参数类型不匹配抛 500）
        return repo.countByStatus(Registration.Status.valueOf(status));
    }

    public Registration findById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在: " + id));
    }
}
