package com.lingyao.platform.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 触发 staging 部署请求 DTO — V2.0.5 R-7
 *
 * jarPath: 待部署的 jar 绝对路径（部署脚本会 scp + mv 到 /opt/lingyao/staging/）
 */
@Data
public class DeployStagingRequest {

    @NotBlank(message = "jar 路径不能为空")
    private String jarPath;
}