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
}
