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
    /**
     * V2.0.21 P1.4 阶段 B：refresh_token（30 天有效，用于换新 access_token）
     * - access_token 过期时前端静默调 /api/auth/refresh 换新
     * - 旧 refresh_token 用一次即失效（滑动续期）
     */
    private String refreshToken;
    /** refresh_token 有效期（秒）— 默认 30*24*3600 = 2592000 */
    private long refreshTokenExpiresIn;
    private UserView user;
    private CompanyView company;
    private List<ProductView> products;

    /**
     * 是否必须修改密码（私有化部署首登强制改密）
     * true  → 前端强制跳转 /change-password 页面
     * false → 正常进入主页
     */
    private boolean mustChangePassword;

    /** V2.0.42：true = 密码正确但需要 2FA 验证码（前端弹 TOTP 输入框，随下次登录带 totpCode 重试） */
    private boolean mfaRequired;

    /** V2.0.42：MFA_REQUIRED（未携带验证码）/ MFA_INVALID（验证码错误） */
    private String mfaReason;

    /** V2.0.66：2FA 挑战凭证——密码+验证码已验过，重试登录只需 totpCode + 此 ticket（免重复过图形验证码） */
    private String captchaTicket;

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
