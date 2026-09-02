package com.lingyao.platform.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * V2.0.28 方案A：一步创建公司 + 公司管理员账号
 *
 * 主人 2026-09-02 拍板（方案 A+B）：
 * 新建公司弹窗内联建管理员（可选区块）——填写则一次事务完成
 * 公司(ACTIVE) + 管理员(SUPER_ADMIN) + 绑定 + 授权；不填则行为同旧版（仅建 PENDING 公司）。
 */
@Data
public class CreateCompanyWithAdminRequest {

    @Valid
    @NotNull(message = "公司信息不能为空")
    private CreateCompanyRequest company;

    /** 管理员信息——null 或 username 为空时跳过建号（保持旧行为） */
    @Valid
    private AdminRef admin;

    /**
     * V2.0.29：发送试用邀请邮件（默认 30 天）
     * true 时（主人 2026-09-02 拍板；V2 变更同日 09:21/09:22）：
     * 1. licensePlan 强制 TRIAL，licenseEnd = licenseStart + trialDays
     * 2. 按【实际开通的产品】生成邮件介绍（开通哪个写哪个，不再限定 GEO）
     * 3. 邮件内不出现内部代号，用对外正式产品名
     * 4. 事务提交后向 admin.email 异步发送试用邀请邮件
     */
    private Boolean trialInvite = false;

    /** 试用天数（默认 30，仅 trialInvite=true 时生效） */
    @Min(value = 1, message = "试用天数至少 1 天")
    @Max(value = 365, message = "试用天数最多 365 天")
    private Integer trialDays = 30;

    @Data
    public static class AdminRef {

        @NotBlank(message = "管理员用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度 3-32")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "用户名仅允许字母/数字/点/下划线/连字符")
        private String username;

        /** 初始密码：留空则服务端自动生成 12 位强密码（响应中返回，仅显示一次） */
        @Size(min = 8, max = 64, message = "初始密码长度 8-64")
        private String initialPassword;

        @Size(max = 64)
        private String displayName;

        @Size(max = 128)
        @Pattern(regexp = "^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$|^$", message = "邮箱格式错误")
        private String email;

        @Size(max = 32)
        private String phone;
    }
}
