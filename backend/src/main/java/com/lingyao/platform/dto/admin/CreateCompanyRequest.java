package com.lingyao.platform.dto.admin;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

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

    /**
     * V2.0.77.13：GEOM 棱镜套餐（basic/pro/flagship），契约 GEOM_quota_contract.md §1
     * 缺省 basic — M1 前端选择器尚未上线，主人拍板「主站先 M2 上线，默认 basic」
     */
    @Pattern(regexp = "basic|pro|flagship", message = "GEOM 套餐必须为 basic/pro/flagship")
    private String geomPlan = "basic";

    /**
     * V2.0.77.14 M4：年费档（人民币元/年）
     * 契约 §4 M4：20 万（basic）/ 48 万（pro）/ 98 万（flagship）
     * 缺省 200000（basic 档）
     */
    @Min(value = 0, message = "年费不能为负")
    @Max(value = 100000000, message = "年费最多 1 亿")
    private Integer annualFeeYuan = 200000;

    /**
     * V2.0.77.14 M4：合同模板档（NONE/BASIC/PRO/FLAGSHIP）
     * 缺省 NONE（待签约）
     */
    @Pattern(regexp = "NONE|BASIC|PRO|FLAGSHIP", message = "合同模板必须为 NONE/BASIC/PRO/FLAGSHIP")
    private String contractTemplate = "NONE";

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

    /** 是否自动开通全部产品（V2.0.57：仅部署就绪的 ACTIVE 产品，未部署产品永不自动开通） */
    private Boolean grantAllProducts = false;

    /**
     * V2.0.57：按产品 code 精确开通（报名转开通主路径）。
     * 与 grantAllProducts 二选一：productCodes 非空时优先；两者都会被「部署就绪」硬过滤收敛。
     */
    @Size(max = 16, message = "productCodes 最多 16 个")
    private List<String> productCodes;
}
