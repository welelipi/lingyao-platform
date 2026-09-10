package com.lingyao.platform.service;

import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.repository.SysUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 敏感操作 OTP 二次验证服务 — V2.0.77.15
 *
 * 设计目标：V2.0.69 A+D+B 方案扩展 + V2.0.67→.68 回滚教训（不增登录摩擦）
 * - 已登录用户做高危操作时，强制二次 OTP 验证（与改密码 OTP 同模式）
 * - 不引入 TOTP（保留 TwoFactorService 但不启用），复用邮件 OTP 通道
 *
 * 流程（前端 → 后端）：
 *   1. 前端敏感操作按钮 → 弹 OTP 弹窗（"敏感操作二次验证"）
 *   2. 前端 POST /api/auth/sensitive-otp/send → 后端按 username 邮箱发码
 *   3. 用户输 OTP → 前端 POST 原敏感端点，body 带 emailOtpCode
 *   4. 后端 controller 首行调用 verify(operation, emailOtpCode, currentUsername)
 *      - 成功 → 继续原业务
 *      - 失败 → 抛 IllegalArgumentException("敏感操作验证码错误或已过期")
 *
 * 与 V2.0.69 改密 OTP 的差异：
 * - 改密 OTP 10 分钟内可重复使用（同一用户连续改密不重复发码）
 * - 敏感操作 OTP 一次性（每次操作前必须重新验证，防止 session 截获后滥用）
 *
 * V2.0.77.17（R5-07 / ARCH-001 / SEC-06 后半）：删除 ticket 两段式死代码
 * （verifyAndIssueTicket / verifyTicket / ticketStore / /ticket 端点）——
 * 全项目零业务调用方 + verifyTicket remove-then-check 非原子（ARCH-001），
 * 两套机制并存徒增维护面。YAGNI：未来需要「一次验证多操作」时重写即可。
 * 现只保留 verify() 单机制。
 *
 * V2.0.77.18（R5-11 / ARCH-009）：改构造器注入（利于 mock 单测）。
 *
 * 6 个接入点（V2.0.77.15 一次性接通）：
 * 1. PUT /api/admin/companies/{id}                       → COMPANY_CHANGE_GEOM_OR_CONTRACT
 * 2. POST /api/admin/companies/{id}/suspend             → COMPANY_SUSPEND
 * 3. POST /api/admin/companies/{id}/activate            → COMPANY_ACTIVATE
 * 4. POST /api/admin/users/{userId}/reset-password      → ADMIN_RESET_USER_PASSWORD
 * 5. PUT /api/company-admin/users/{userId}/role         → CHANGE_USER_ROLE
 * 6. PUT /api/company-admin/sso-config                  → CHANGE_SSO_CONFIG
 */
@Service
@Slf4j
public class SensitiveOtpService {

    private final EmailOtpService emailOtpService;
    private final SysUserRepository sysUserRepo;

    /** V2.0.77.18（R5-11 / ARCH-009）：构造器注入，替代 @Autowired 字段注入 */
    public SensitiveOtpService(EmailOtpService emailOtpService, SysUserRepository sysUserRepo) {
        this.emailOtpService = emailOtpService;
        this.sysUserRepo = sysUserRepo;
    }

    /**
     * 发送敏感操作 OTP（公开端点，按当前已登录用户发码）
     */
    public Map<String, Object> sendSensitiveOtp(String currentUsername, String operation) {
        SysUser user = sysUserRepo.findByUsername(currentUsername).orElse(null);
        if (user == null) return Map.of("ok", false, "message", "当前用户不存在");
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return Map.of("ok", false, "message", "当前账号未绑定邮箱，无法接收敏感操作验证码");
        }
        // 复用 EmailOtpService 通道 + 新用途标识
        var result = emailOtpService.sendOtp(EmailOtpService.PURPOSE_SENSITIVE_OPERATION, currentUsername, user.getEmail());
        if (Boolean.TRUE.equals(result.get("ok"))) {
            log.info("敏感操作 OTP 已发送 user={} operation={}", currentUsername, operation);
        }
        return result;
    }

    /**
     * Controller 顶部直接调用的单机制版本：
     * 1. 前端在请求 body 带 emailOtpCode
     * 2. controller 首行 verify(operation, emailOtpCode, currentUsername)
     * 3. 失败抛异常，成功继续业务
     */
    public void verify(String operation, String emailOtpCode, String currentUsername) {
        if (emailOtpCode == null || emailOtpCode.isBlank()) {
            throw new IllegalArgumentException("敏感操作验证码不能为空（请先弹窗发码）");
        }
        String err = emailOtpService.verifyOtp(EmailOtpService.PURPOSE_SENSITIVE_OPERATION, currentUsername, emailOtpCode);
        if (err != null) {
            throw new IllegalArgumentException("敏感操作验证码错误或已过期");
        }
        log.info("敏感操作 OTP 直接校验通过 user={} operation={}", currentUsername, operation);
    }
}
