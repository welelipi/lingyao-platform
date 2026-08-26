package com.lingyao.platform.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InvitationCreateRequest {

    @NotNull
    private Long companyId;

    @NotNull
    @Pattern(regexp = "GEO|HPD|AIDD|POR", message = "产品 code 非法")
    private String productCode;

    @NotNull
    @Pattern(regexp = "OPERATOR|SENIOR_OPERATOR|SUPER_ADMIN",
            message = "角色 code 非法")
    private String roleCode;

    @Size(max = 128)
    @Pattern(regexp = "^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$|^$",
            message = "邀请邮箱格式错误")
    private String invitedEmail;

    /** 有效期（天），可选，默认 7 */
    private Integer expiresDays;

    @Size(max = 256)
    private String remark;
}
