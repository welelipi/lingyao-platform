package com.lingyao.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录响应
 * 包含：JWT + 用户信息 + 公司信息 + 可见产品列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private long expiresIn;
    private UserView user;
    private CompanyView company;
    private List<ProductView> products;

    /**
     * 是否必须修改密码（私有化部署首登强制改密）
     * true  → 前端强制跳转 /change-password 页面
     * false → 正常进入主页
     */
    private boolean mustChangePassword;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserView {
        private Long id;
        private String username;
        private String displayName;
        private String email;
        private boolean platformAdmin;
        // === V2.0.16 W1/W4 新增：与 JWT 平铺字段 is_tenant_admin 保持一致 ===
        private boolean tenantAdmin;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyView {
        private Long id;
        private String code;
        private String name;
        private String licensePlan;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductView {
        private Long id;
        private String code;
        private String name;
        private String description;
        private String icon;
        private boolean granted;
        private boolean licensed;
        private String roleCode;
        private String roleName;
        private String entryPath;
    }
}
