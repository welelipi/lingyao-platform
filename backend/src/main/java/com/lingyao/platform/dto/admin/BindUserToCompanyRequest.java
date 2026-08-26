package com.lingyao.platform.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 把已有用户绑定到公司 — P1-B
 */
@Data
public class BindUserToCompanyRequest {

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    @NotNull(message = "公司 ID 不能为空")
    private Long companyId;

    @Pattern(regexp = "SUPER_ADMIN|OPERATOR|VIEWER", message = "公司内角色必须为 SUPER_ADMIN/OPERATOR/VIEWER")
    private String companyRole = "OPERATOR";
}
