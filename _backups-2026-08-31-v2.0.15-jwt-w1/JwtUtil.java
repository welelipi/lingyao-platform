package com.lingyao.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 工具类 — Phase 2 解耦版
 *
 * Token Payload（主人多租户设计核心 + Phase 2 解耦）：
 *
 * 主网站内部使用（平铺字段，向后兼容）：
 * - uid: 用户ID
 * - username: 用户名
 * - cid: 公司ID（多租户隔离关键）
 * - ccode: 公司 code
 * - pcode: 当前产品 code（可选，向后兼容字段）
 * - rcode: 当前产品内角色 code（可选，向后兼容字段）
 * - adm: 是否平台超管
 *
 * 给子产品的标准化断言（Phase 2 新增，子产品只看这三段）：
 * - tenant: { id, code, name }   — 租户契约
 * - identity: { user_id, username, display_name } — 身份契约
 * - grants: { product_code, authorized, authorized_at, authorized_by } — 主网站门禁断言
 *
 * 主网站内部不再依赖 pcode/rcode 做权限判断，子产品也不需要懂 pcode/rcode。
 * 老字段保留 1 周过渡期（2026-08-19 后移除）。
 */
@Component
public class JwtUtil {

    @Value("${lingyao.jwt.secret}")
    private String secret;

    @Value("${lingyao.jwt.expiration-hours:24}")
    private long expirationHours;

    @Value("${lingyao.jwt.issuer:lingyao-platform}")
    private String issuer;

    // V2.0.11+ HPD SSO 改造：JWT audience 标记，主仓签发给子产品的 token 都带这个 audience
    private static final String DEFAULT_AUDIENCE = "lingyao-sso";

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT — Phase 2 解耦版
     *
     * 主网站登录时调用：
     * - productCode/roleCode 仍可传入（兼容老调用方），写入老字段
     * - 自动生成 tenant/identity/grants 三段断言（标准化给子产品用）
     *
     * @param displayName 用户显示名（来自 CompanyUser.displayName）
     * @param companyName 公司显示名（来自 Company.name）
     * @param authorizedBy 授权人用户名（主网站超管 或 公司管理员）
     */
    public String generate(Long userId, String username, Long companyId, String companyCode,
                          boolean isPlatformAdmin, String productCode, String roleCode,
                          String displayName, String companyName, String authorizedBy) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("username", username);
        claims.put("cid", companyId);
        claims.put("ccode", companyCode);
        claims.put("adm", isPlatformAdmin);

        // === Phase 2 新增：标准化三段断言 ===

        // tenant：租户契约
        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("id", companyId == null ? null : String.valueOf(companyId));
        tenant.put("code", companyCode);
        tenant.put("name", companyName == null ? companyCode : companyName);
        claims.put("tenant", tenant);

        // identity：身份契约
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("user_id", userId == null ? null : String.valueOf(userId));
        identity.put("username", username);
        identity.put("display_name", displayName == null ? username : displayName);
        claims.put("identity", identity);

        // grants：主网站门禁断言（本次登录所属产品的授权）
        Map<String, Object> grants = new LinkedHashMap<>();
        if (productCode != null) {
            grants.put("product_code", productCode);
            grants.put("authorized", true);
            grants.put("authorized_at", new Date().toInstant().toString());
            grants.put("authorized_by", authorizedBy == null ? "system" : authorizedBy);
        }
        claims.put("grants", grants);

        // === 老字段保留 1 周过渡期（2026-08-19 后移除）===
        if (productCode != null) claims.put("pcode", productCode);
        if (roleCode != null) claims.put("rcode", roleCode);

        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationHours * 3600 * 1000);

        return Jwts.builder()
                .claims(claims)
                .issuer(issuer)
                .audience().add(DEFAULT_AUDIENCE).and()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(exp)
                .signWith(getKey())
                .compact();
    }

    /**
     * 向后兼容的 generate 方法（不传 displayName 等新参数）
     * 1 周后删除
     */
    @Deprecated
    public String generate(Long userId, String username, Long companyId, String companyCode,
                          boolean isPlatformAdmin, String productCode, String roleCode) {
        return generate(userId, username, companyId, companyCode, isPlatformAdmin,
                productCode, roleCode, null, null, null);
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return expirationHours * 3600;
    }

    /**
     * 检测 token 是否为 Phase 2 新格式（含 tenant/identity/grants）
     */
    public boolean isNewFormat(Claims claims) {
        return claims.containsKey("tenant") && claims.containsKey("identity");
    }
}