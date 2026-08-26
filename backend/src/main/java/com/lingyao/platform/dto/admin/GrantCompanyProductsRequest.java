package com.lingyao.platform.dto.admin;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 为公司开通多个产品 — P1-B
 */
@Data
public class GrantCompanyProductsRequest {

    @NotNull(message = "公司 ID 不能为空")
    private Long companyId;

    @NotEmpty(message = "至少开通一个产品")
    private List<Long> productIds;

    /** 可选：最大用户数（不传则默认 100） */
    private Integer maxUsers = 100;

    /** 可选：有效期年数（不传则默认 10） */
    private Integer licenseYears = 10;
}
