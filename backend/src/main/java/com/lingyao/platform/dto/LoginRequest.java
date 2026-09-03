package com.lingyao.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求 DTO
 *
 * 主人决策：先用用户名密码（手机号短信验证后续再接）
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 可选：登录后跳转的产品 code（GEO/HPD/AIDD/POR） */
    private String targetProduct;

    /** 可选：公司 code（多公司用户需指定） */
    private String companyCode;

    /** 可选：2FA TOTP 6 位验证码 / 备份码（V2.0.42：启用 2FA 的用户必须携带） */
    private String totpCode;

    /** V2.0.66：图形验证码 id（GET /api/auth/captcha 返回） */
    private String captchaId;

    /** V2.0.66：图形验证码答案（算术题计算结果） */
    private String captchaAnswer;

    /** V2.0.66：2FA 挑战凭证（mfaRequired 响应返回，重试登录携带它可免重复过图形验证码） */
    private String captchaTicket;
}
