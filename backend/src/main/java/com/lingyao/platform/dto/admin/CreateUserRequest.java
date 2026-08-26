package com.lingyao.platform.dto.admin;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * 平台超管创建用户请求 — P1-B
 *
 * 创建用户后，需要单独调 /api/admin/companies/{id}/users 把用户加入公司。
 */
@Data
public class CreateUserRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度 3-32")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "用户名仅允许字母/数字/点/下划线/连字符")
    private String username;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 64, message = "初始密码长度 8-64")
    private String initialPassword;

    @Size(max = 64)
    private String displayName;

    @Size(max = 128)
    @Pattern(regexp = "^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$|^$", message = "邮箱格式错误")
    private String email;

    @Size(max = 32)
    private String phone;

    /** 是否平台超管（仅顶级平台管理员可创建） */
    private Boolean platformAdmin = false;

    /** 立即绑定的公司列表（每条指定 companyId + 公司内角色） */
    private List<BindCompanyRef> memberships;

    /** 立即授权的产品列表（productId 列表，作用于第一个 membership 公司） */
    private List<Long> productIds;

    /**
     * 公司绑定参考
     */
    @Data
    public static class BindCompanyRef {
        @NotNull(message = "公司 ID 不能为空")
        private Long companyId;

        @Pattern(regexp = "SUPER_ADMIN|OPERATOR|VIEWER", message = "公司内角色必须为 SUPER_ADMIN/OPERATOR/VIEWER")
        private String companyRole = "OPERATOR";
    }
}
