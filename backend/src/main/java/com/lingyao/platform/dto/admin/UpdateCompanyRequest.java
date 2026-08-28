package com.lingyao.platform.dto.admin;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台超管更新公司请求 — V2.0.10
 *
 * 字段：除 code 外，其他字段均可改。code 是公司唯一标识，改动风险大，禁止编辑。
 *
 * V2.0.10 改进：
 * - 加 licenseStart/licenseEnd 让大超管在 UI 上设置有效期
 * - 状态枚举从 3 种扩到 5 种（PENDING/ACTIVE/EXPIRED/SUSPENDED/DELETED）
 *
 * 二次确认（前端强制）：
 * - 修改部署模式（SAAS ↔ PRIVATE）
 * - 修改许可证截止日期
 * - 修改状态（暂停/恢复/删除）
 */
@Data
public class UpdateCompanyRequest {

    @NotBlank(message = "公司名称不能为空")
    @Size(min = 2, max = 128, message = "公司名称长度 2-128")
    private String name;

    /** 部署模式：SAAS（共享云）/ PRIVATE（私有化部署） */
    @NotNull(message = "部署模式不能为空")
    @Pattern(regexp = "SAAS|PRIVATE", message = "部署模式必须为 SAAS 或 PRIVATE")
    private String deploymentMode;

    /** 许可证等级：TRIAL / STANDARD / ENTERPRISE */
    @NotNull(message = "许可证等级不能为空")
    @Pattern(regexp = "TRIAL|STANDARD|ENTERPRISE", message = "许可证等级必须为 TRIAL/STANDARD/ENTERPRISE")
    private String licensePlan;

    /** 公司状态：PENDING / ACTIVE / EXPIRED / SUSPENDED / DELETED */
    @NotNull(message = "公司状态不能为空")
    @Pattern(regexp = "PENDING|ACTIVE|EXPIRED|SUSPENDED|DELETED",
            message = "公司状态必须为 PENDING/ACTIVE/EXPIRED/SUSPENDED/DELETED")
    private String status;

    /** 许可证起始时间（可选；为 null 时保持原值） */
    private LocalDateTime licenseStart;

    /** 许可证截止时间（可选；为 null 时保持原值） */
    private LocalDateTime licenseEnd;

    @Min(value = 1, message = "最大用户数至少为 1")
    @Max(value = 10000, message = "最大用户数最多 10000")
    private Integer maxUsers;

    @Size(max = 128)
    @Pattern(regexp = "^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$|^$", message = "联系邮箱格式错误")
    private String contactEmail;

    @Size(max = 32)
    private String contactPhone;

    @Size(max = 256)
    private String address;

    @Size(max = 512)
    private String remark;
}