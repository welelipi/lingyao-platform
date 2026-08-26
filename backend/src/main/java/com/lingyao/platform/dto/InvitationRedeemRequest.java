package com.lingyao.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InvitationRedeemRequest {

    @NotBlank
    private String token;

    @NotBlank
    @Size(min = 3, max = 64, message = "用户名长度 3-64")
    @Pattern(regexp = "^[a-zA-Z0-9_\\-]+$", message = "用户名只能含字母数字下划线横线")
    private String username;

    @NotBlank
    @Size(min = 6, max = 64, message = "密码长度 6-64")
    private String password;

    @Pattern(regexp = "^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$|^$",
            message = "邮箱格式错误")
    private String email;

    @Size(max = 64)
    private String displayName;
}
