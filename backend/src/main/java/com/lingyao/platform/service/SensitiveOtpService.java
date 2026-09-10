package com.lingyao.platform.service;

import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.repository.SysUserRepository;
import com.lingyao.platform.security.JwtAuthFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * - 敏感操作 OTP 5 分钟一次性（每次操作前必须重新验证，防止 session 截获后滥用）
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

    @Autowired private EmailOtpService emailOtpService;
    @Autowired private SysUserRepository sysUserRepo;

    /** 敏感操作 OTP ticket（绑定 username + operation，5 分钟一次性） */
    private record Ticket(String username, String operation, LocalDateTime expiresAt) {}

    private static final long TICKET_TTL_SECONDS = 300;  // 5 分钟
    private static final long MAX_TICKETS = 5000;        // 防刷

    private final Map<String, Ticket> ticketStore = new ConcurrentHashMap<>();

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
     * 校验 OTP 并签发 ticket（5 分钟一次性）
     */
    public String verifyAndIssueTicket(String currentUsername, String operation, String otpCode) {
        if (otpCode == null || otpCode.isBlank()) {
            throw new IllegalArgumentException("敏感操作验证码不能为空");
        }
        // 调 EmailOtpService 校验（一次性消费）
        String err = emailOtpService.verifyOtp(EmailOtpService.PURPOSE_SENSITIVE_OPERATION, currentUsername, otpCode);
        if (err != null) {
            throw new IllegalArgumentException("敏感操作验证码错误或已过期");
        }
        // 签发一次性 ticket
        cleanupIfNeeded();
        String ticket = java.util.UUID.randomUUID().toString().replace("-", "");
        ticketStore.put(ticket, new Ticket(currentUsername, operation, LocalDateTime.now().plusSeconds(TICKET_TTL_SECONDS)));
        log.info("敏感操作 OTP 校验通过 ticket={} operation={}", ticket.substring(0, 8) + "***", operation);
        return ticket;
    }

    /**
     * 验证 ticket 是否有效（一次性消费）
     * 用于 controller 切面校验：方法 body 携带 sensitiveTicket，服务端先验票再执行业务
     */
    public void verifyTicket(String sensitiveTicket, String currentUsername, String operation) {
        if (sensitiveTicket == null || sensitiveTicket.isBlank()) {
            throw new IllegalArgumentException("敏感操作校验凭证缺失");
        }
        Ticket ticket = ticketStore.remove(sensitiveTicket);  // 一次性
        if (ticket == null) {
            throw new IllegalArgumentException("敏感操作校验凭证无效或已使用");
        }
        if (ticket.expiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("敏感操作校验凭证已过期，请重新输入邮箱验证码");
        }
        if (!ticket.username().equals(currentUsername)) {
            throw new IllegalArgumentException("敏感操作校验凭证与当前用户不匹配");
        }
        if (!ticket.operation().equals(operation)) {
            throw new IllegalArgumentException("敏感操作校验凭证与操作类型不匹配");
        }
    }

    /**
     * Controller 顶部直接调用版本（不引入 ticket 概念）：
     * 1. 前端在请求 body 带 emailOtpCode
     * 2. controller 首行 verify(operation, emailOtpCode, currentUsername)
     * 3. 失败抛异常，成功继续业务
     * 这是 V2.0.77.15 接入的简化版（避免前端先调 send 再 submit 的两段式）
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

    private void cleanupIfNeeded() {
        if (ticketStore.size() > MAX_TICKETS) {
            LocalDateTime now = LocalDateTime.now();
            ticketStore.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        }
    }
}