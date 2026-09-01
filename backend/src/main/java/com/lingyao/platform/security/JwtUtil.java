package com.lingyao.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
                          String displayName, String companyName, String authorizedBy,
                          boolean isTenantAdmin, List<String> subProductGrants) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("username", username);
        claims.put("cid", companyId);
        claims.put("ccode", companyCode);
        claims.put("adm", isPlatformAdmin);

        // === V2.0.16 W1 新增：平铺字段（子产品 DINFO M2 直接读）===
        claims.put("tenant_name", companyName == null ? companyCode : companyName);
        claims.put("is_tenant_admin", isTenantAdmin);
        claims.put("sub_product_grants", subProductGrants == null ? Collections.emptyList() : subProductGrants);

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
     * 向后兼容的 generate 方法（V2.0.16 之前的 10 参版本，不含 W1 平铺字段）
     * W1 子产品用平铺字段；老调用方保留兼容入口
     */
    @Deprecated
    public String generate(Long userId, String username, Long companyId, String companyCode,
                          boolean isPlatformAdmin, String productCode, String roleCode,
                          String displayName, String companyName, String authorizedBy) {
        return generate(userId, username, companyId, companyCode, isPlatformAdmin,
                productCode, roleCode, displayName, companyName, authorizedBy,
                isPlatformAdmin, Collections.emptyList());
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

    // ═══════════════════════ V2.0.19 新增：SSO Token 生成 ═══════════════════════

    /**
     * SSO callback 专用的 JWT 生成（飞书/钉钉/企微登录后用）
     *
     * 在标准 claims 基础上新增 6 个 SSO 专用 claim（子平台用于首次扫码自动建账号）：
     * - department_id: 飞书部门 ID / 钉钉 dept_id / 企微 department
     * - employee_id: 主平台员工唯一 ID（三方 employee_id / unionid / userid）
     * - source: SSO 来源（FEISHU / DINGTALK / WECOM / GENERIC_OIDC）
     * - sso_status: SSO 登录结果（PENDING_REVIEW / ACTIVE）
     * - bu: 凌瑶 BU（按 dept_mapping 映射）
     * - org_code: 凌瑶组织 code（预留）
     */
    public String generateSsoToken(String employeeId, Long companyId, String subProductCode,
                                    String source, String departmentId, String bu) {
        Map<String, Object> claims = new HashMap<>();
        // uid 用 employeeId（飞书 open_id 等），子平台按此字段做员工匹配
        claims.put("uid", employeeId);
        claims.put("username", employeeId);
        claims.put("cid", companyId);
        claims.put("ccode", null);
        claims.put("adm", false);

        // V2.0.19 新增 6 个 SSO claim
        claims.put("source", source);
        claims.put("employee_id", employeeId);
        claims.put("department_id", departmentId);
        claims.put("sso_status", "PENDING_REVIEW");   // 默认 PENDING，超管分配后改 ACTIVE
        claims.put("bu", bu);
        claims.put("org_code", null);   // 预留

        // 平铺字段（兼容 V2.0.16+ 子产品）
        claims.put("tenant_name", null);
        claims.put("is_tenant_admin", false);
        claims.put("sub_product_grants", Collections.singletonList(subProductCode));

        // 三段断言（子产品标准化解析）
        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("id", companyId == null ? null : String.valueOf(companyId));
        tenant.put("code", null);
        tenant.put("name", null);
        claims.put("tenant", tenant);

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("user_id", employeeId);
        identity.put("username", employeeId);
        identity.put("display_name", null);
        identity.put("source", source);
        identity.put("department_id", departmentId);
        identity.put("bu", bu);
        claims.put("identity", identity);

        Map<String, Object> grants = new LinkedHashMap<>();
        grants.put("product_code", subProductCode);
        grants.put("authorized", true);
        grants.put("authorized_at", new Date().toInstant().toString());
        grants.put("authorized_by", "sso:" + source);
        grants.put("via_sso", true);
        claims.put("grants", grants);

        Date now = new Date();
        // SSO token 短期有效（2 小时），首次登录后子平台应该换发长 token
        Date exp = new Date(now.getTime() + 2 * 3600 * 1000);

        return Jwts.builder()
                .claims(claims)
                .issuer(issuer)
                .audience().add(DEFAULT_AUDIENCE).and()
                .subject(employeeId)
                .issuedAt(now)
                .expiration(exp)
                .signWith(getKey())
                .compact();
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

    // ═══════════════════════ V2.0.19 W6 阶段 2 新增：专属链接短时 JWT（5 分钟过期）═══════════════════════

    /**
     * 专属链接短时 JWT — 5 分钟过期（300 秒）
     *
     * 用途：公司超管在 portal.html 点"复制专属链接"按钮时，主平台后端生成一个 5 分钟过期的 JWT，
     *       员工点链接 → 子平台 GET /api/sub-product/{code}/verify-link 验签 → 拿到完整身份信息
     *
     * 短时 JWT payload（最小化原则，仅传递跳转必需的字段）：
     * - cid: 公司 ID
     * - ccode: 公司 code
     * - pcode: 目标子产品 code（GEOM / DINFO / MPDM / AIDD / PORM / MED-PPT）
     * - rcode: 角色 code（COMPANY_ADMIN / OPERATOR / VIEWER）
     * - uid: 发放人用户 ID（超管）
     * - username: 发放人用户名
     * - generated_at: 生成时间戳（ISO 8601）
     * - nonce: 一次性随机数（防止链接被复用）
     * - audience: "lingyao-sublink"（与主网站 JWT 区分）
     *
     * 安全性：
     * - 5 分钟过期（主人拍板 5 分钟窗口）
     * - nonce 防重放（子平台可记录已用的 nonce）
     * - 验签需要 LINGYAO_JWT_SECRET_BASE64（与主网站其他 JWT 共享密钥）
     *
     * @since V2.0.19 W6 阶段 2 后端
     */
    private static final String SHORT_LINK_AUDIENCE = "lingyao-sublink";
    private static final long SHORT_LINK_TTL_SECONDS = 300;   // 5 分钟

    public String generateShortLinkToken(Long companyId, String companyCode,
                                         String productCode, String roleCode,
                                         Long issuedByUserId, String issuedByUsername) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("cid", companyId);
        claims.put("ccode", companyCode);
        claims.put("pcode", productCode);
        claims.put("rcode", roleCode);
        claims.put("uid", issuedByUserId);
        claims.put("username", issuedByUsername);
        claims.put("generated_at", new Date().toInstant().toString());
        claims.put("nonce", java.util.UUID.randomUUID().toString());
        // 明确标记为短时专属链接 token
        claims.put("link_type", "SUB_PRODUCT_ENTRY");

        Date now = new Date();
        Date exp = new Date(now.getTime() + SHORT_LINK_TTL_SECONDS * 1000);

        return Jwts.builder()
                .claims(claims)
                .issuer(issuer)
                .audience().add(SHORT_LINK_AUDIENCE).and()
                .subject(String.valueOf(companyId) + ":" + productCode)
                .issuedAt(now)
                .expiration(exp)
                .signWith(getKey())
                .compact();
    }

    /**
     * 解析并校验短时专属链接 token
     *
     * 校验项：
     * - 签名（用 LINGYAO_JWT_SECRET_BASE64）
     * - audience = "lingyao-sublink"（区分其他 JWT 类型）
     * - link_type = "SUB_PRODUCT_ENTRY"（区分其他 JWT 类型）
     * - 未过期（5 分钟内）
     *
     * @return Claims 包含 cid / ccode / pcode / rcode / uid / username / nonce
     * @throws io.jsonwebtoken.JwtException 验签失败 / 过期 / audience 不匹配
     */
    public Claims parseShortLinkToken(String token) {
        Claims claims = parse(token);   // 已做签名 + 过期校验
        if (!SHORT_LINK_AUDIENCE.equals(claims.getAudience().stream()
                .filter(a -> SHORT_LINK_AUDIENCE.equals(a)).findFirst().orElse(null))) {
            throw new io.jsonwebtoken.JwtException("Token audience 不匹配（非专属链接 token）");
        }
        if (!"SUB_PRODUCT_ENTRY".equals(claims.get("link_type"))) {
            throw new io.jsonwebtoken.JwtException("Token link_type 不匹配（非专属链接 token）");
        }
        return claims;
    }

    /**
     * 短时专属链接的 TTL（秒）— 用于前端展示倒计时
     */
    public long getShortLinkTtlSeconds() {
        return SHORT_LINK_TTL_SECONDS;
    }
}