package com.lingyao.platform.security;

import com.lingyao.platform.repository.SysUserRepository;
import com.lingyao.platform.service.EmailOtpService;
import com.lingyao.platform.service.SensitiveOtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V2.0.77.18（R5-11 / ARCH-009）：SensitiveOtpService 单元测试。
 * 构造器注入后可直接 new + Mockito mock，无需 Spring 上下文。
 *
 * 覆盖面：
 * 1. verify() 空 OTP fail-closed（「不能为空」，不发码不查库）
 * 2. verify() OTP 错误 → 抛「敏感操作验证码错误或已过期」
 * 3. verify() OTP 正确 → 静默通过（不抛即过）
 * 4. sendSensitiveOtp() 用户不存在 → 返回 ok=false
 * 5. sendSensitiveOtp() 用户无邮箱 → 返回提示
 * 6. sendSensitiveOtp() 正常路径 → 透传 EmailOtpService 结果
 */
class SensitiveOtpServiceTest {

    private EmailOtpService emailOtpService;
    private SysUserRepository sysUserRepo;
    private SensitiveOtpService service;

    @BeforeEach
    void setUp() {
        emailOtpService = mock(EmailOtpService.class);
        sysUserRepo = mock(SysUserRepository.class);
        service = new SensitiveOtpService(emailOtpService, sysUserRepo);
    }

    @Test
    @DisplayName("verify: 空 OTP → fail-closed 抛「不能为空」，且不触达 EmailOtpService")
    void verifyBlankOtpFailsClosed() {
        assertThatThrownBy(() -> service.verify("COMPANY_SUSPEND", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.verify("COMPANY_SUSPEND", "   ", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        Mockito.verifyNoInteractions(emailOtpService);
    }

    @Test
    @DisplayName("verify: OTP 错误 → 抛统一文案「敏感操作验证码错误或已过期」")
    void verifyWrongOtpThrows() {
        when(emailOtpService.verifyOtp(eq(EmailOtpService.PURPOSE_SENSITIVE_OPERATION),
                eq("admin"), eq("000000"))).thenReturn("验证码错误，剩余尝试次数 4");
        assertThatThrownBy(() -> service.verify("COMPANY_SUSPEND", "000000", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感操作验证码错误或已过期");
    }

    @Test
    @DisplayName("verify: OTP 正确 → 静默通过（不抛异常即成功）")
    void verifyCorrectOtpPasses() {
        when(emailOtpService.verifyOtp(eq(EmailOtpService.PURPOSE_SENSITIVE_OPERATION),
                eq("admin"), eq("123456"))).thenReturn(null);
        assertThatCode(() -> service.verify("COMPANY_SUSPEND", "123456", "admin"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sendSensitiveOtp: 用户不存在 → ok=false")
    void sendOtpUserNotFound() {
        when(sysUserRepo.findByUsername("ghost")).thenReturn(Optional.empty());
        var result = service.sendSensitiveOtp("ghost", "COMPANY_SUSPEND");
        org.assertj.core.api.Assertions.assertThat(result.get("ok")).isEqualTo(false);
    }

    @Test
    @DisplayName("sendSensitiveOtp: 用户未绑定邮箱 → 返回提示")
    void sendOtpNoEmail() {
        com.lingyao.platform.entity.SysUser u = new com.lingyao.platform.entity.SysUser();
        u.setUsername("noemail");
        when(sysUserRepo.findByUsername("noemail")).thenReturn(Optional.of(u));
        var result = service.sendSensitiveOtp("noemail", "COMPANY_SUSPEND");
        org.assertj.core.api.Assertions.assertThat(result.get("ok")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat((String) result.get("message")).contains("未绑定邮箱");
    }

    @Test
    @DisplayName("sendSensitiveOtp: 正常路径 → 透传 EmailOtpService 结果")
    void sendOtpSuccess() {
        com.lingyao.platform.entity.SysUser u = new com.lingyao.platform.entity.SysUser();
        u.setUsername("admin");
        u.setEmail("admin@example.com");
        when(sysUserRepo.findByUsername("admin")).thenReturn(Optional.of(u));
        when(emailOtpService.sendOtp(anyString(), anyString(), anyString()))
                .thenReturn(java.util.Map.of("ok", true, "message", "验证码已发送"));
        var result = service.sendSensitiveOtp("admin", "COMPANY_SUSPEND");
        org.assertj.core.api.Assertions.assertThat(result.get("ok")).isEqualTo(true);
    }
}
