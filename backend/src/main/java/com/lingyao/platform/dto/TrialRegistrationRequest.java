package com.lingyao.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Trial 注册请求 DTO · V2.0.78.0
 *
 * 字段定义（基于 D-5 主人 7 项拍板）：
 * - phone ② 手机号（11 位 · 主键 · 注册名）
 * - email 邮箱（开通链接 + 周报目标）
 * - companyName 公司名
 * - source 来源渠道（可选 · 默认 "官网首页"）
 */
@Data
public class TrialRegistrationRequest {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误（11 位 · 1 开头）")
    private String phone;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式错误")
    @Size(max = 128)
    private String email;

    @NotBlank(message = "公司名称不能为空")
    @Size(min = 2, max = 128, message = "公司名称长度需 2~128 字符")
    private String companyName;

    @Size(max = 64)
    private String source = "官网首页";
}
