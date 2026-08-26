package com.lingyao.platform.dto.admin;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 给某用户授予某公司的产品访问权 — P1-B
 */
@Data
public class GrantProductRequest {

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    @NotNull(message = "公司 ID 不能为空")
    private Long companyId;

    @NotEmpty(message = "至少授权一个产品")
    private List<Long> productIds;
}
