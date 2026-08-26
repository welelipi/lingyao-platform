package com.lingyao.platform.dto.admin;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 平台超管创建公司请求 — P1-B
 *
 * 主人决策：私有化部署下不需要此接口（只有 1 家公司）；
 * SAAS 模式下由平台超管在后台手动创建公司。
 */
@Data
public class CreateCompanyRequest {

    @NotBlank(message = "公司名称不能为空")
    @Size(min = 2, max = 128, message = "公司名称长度 2-128")
    private String name;

    /** 唯一 code（英文短码，用于公司 SSO token 识别） */
    @NotBlank(message = "公司 code 不能为空")
    @Pattern(regexp = "^[A-Z0-9_-]{2,32}$", message = "公司 code 必须为大写字母/数字/下划线/连字符，2-32 位")
    private String code;

    /** 部署模式：SAAS（默认）/ PRIVATE */
    @Pattern(regexp = "SAAS|PRIVATE", message = "部署模式必须为 SAAS 或 PRIVATE")
    private String deploymentMode = "SAAS";

    /** 许可证等级：TRIAL / STANDARD / ENTERPRISE */
    @Pattern(regexp = "TRIAL|STANDARD|ENTERPRISE", message = "许可证等级必须为 TRIAL/STANDARD/ENTERPRISE")
    private String licensePlan = "STANDARD";

    @Min(value = 1, message = "最大用户数至少为 1")
    @Max(value = 10000, message = "最大用户数最多 10000")
    private Integer maxUsers = 10;

    @Size(max = 128)
    @Pattern(regexp = "^[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}$|^$", message = "联系邮箱格式错误")
    private String contactEmail;

    @Size(max = 32)
    private String contactPhone;

    @Size(max = 256)
    private String address;

    @Size(max = 512)
    private String remark;

    /** 是否自动开通全部产品（GEO/HPD/AIDD/POR） */
    private Boolean grantAllProducts = false;
}
