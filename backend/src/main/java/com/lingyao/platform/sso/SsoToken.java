package com.lingyao.platform.sso;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OAuth access_token 封装
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SsoToken {
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;        // 秒
    private String tokenType;       // Bearer
}