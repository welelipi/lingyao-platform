package com.lingyao.platform.service;

import com.lingyao.platform.dto.LoginRequest;
import com.lingyao.platform.dto.LoginResponse;
import com.lingyao.platform.entity.*;
import com.lingyao.platform.repository.*;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 认证服务 — Bug-09 修复登录防爆破
 */
@Service
public class AuthService {

    @Autowired private SysUserRepository userRepo;
    @Autowired private CompanyRepository companyRepo;
    @Autowired private CompanyUserRepository companyUserRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private CompanyProductRepository companyProductRepo;
    @Autowired private ProductUserGrantRepository grantRepo;
    @Autowired private ProductRoleRepository roleRepo;
    @Autowired private ProductUserRoleRepository userRoleRepo;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private LoginAttemptService attemptService;
    @Autowired private AuditLogService auditLogService;
    // V2.0.26 P2.4 阶段 A：认证安全审计（独立表 auth_audit_event）
    @Autowired private AuthAuditService authAuditService;
    // V2.0.20 P1 阶段 A
    @Autowired private TokenBlacklistRepository tokenBlacklistRepository;
    // V2.0.21 P1.4 阶段 B：refresh_token 滑动续期
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    // V2.0.21 P1.5 阶段 B：forgot-password
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    // V2.0.42：2FA 登录 challenge
    @Autowired private TwoFactorService twoFactorService;
    // V2.0.66：算术图形验证码
    @Autowired private CaptchaService captchaService;

    public LoginResponse login(LoginRequest req, String ip) {
        // ═══ V2.0.66：图形验证码校验（在锁定检查前——锁定态也要先过验证码，防无脑重试）═══
        if (!captchaService.verify(req.getCaptchaId(), req.getCaptchaAnswer())) {
            authAuditService.recordFailure(
                    AuthAuditEvent.EventType.LOGIN_FAIL,
                    null, req.getUsername(), ip,
                    "/api/auth/login", "POST",
                    "CAPTCHA_INVALID", "图形验证码错误或已过期");
            throw new IllegalArgumentException("验证码错误或已过期，请点击图片刷新后重试");
        }

        // Bug-09：登录前先检查是否锁定
        if (attemptService.isLocked(req.getUsername())) {
            String msg = attemptService.getLockMessage(req.getUsername());
            // V2.0.26 P2.4：登录锁定 → 记录安全审计事件
            authAuditService.recordFailureWithMeta(
                    AuthAuditEvent.EventType.LOGIN_LOCKED,
                    null, req.getUsername(), ip,
                    "/api/auth/login", "POST",
                    "ACCOUNT_LOCKED", msg,
                    java.util.Map.of("username", req.getUsername(), "reason", "Bug-09: 5分钟内5次错误"));
            throw new IllegalArgumentException(msg != null ? msg : "账号已锁定，请稍后重试");
        }

        SysUser user = userRepo.findByUsername(req.getUsername()).orElse(null);
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            attemptService.recordFailure(req.getUsername());
            // V2.0.63：显式回填公司归属（登录时无 CurrentUser，否则 companyId=null 公司视角不可见）
            auditLogService.recordWithSubject("LOGIN_FAIL", "USER",
                    user == null ? null : user.getId(), req.getUsername(),
                    "登录失败: " + req.getUsername());
            // V2.0.26 P2.4：登录失败 → 记录安全审计事件
            int remaining = 5 - (attemptService.isLocked(req.getUsername()) ? 5 : 0);
            authAuditService.recordFailureWithMeta(
                    AuthAuditEvent.EventType.LOGIN_FAIL,
                    user == null ? null : user.getId(),
                    req.getUsername(), ip,
                    "/api/auth/login", "POST",
                    "INVALID_CREDENTIALS",
                    "用户名或密码错误",
                    java.util.Map.of("userExists", user != null, "attemptsRemaining", remaining));
            // Bug-09：剩余尝试次数提示
            throw new IllegalArgumentException("用户名或密码错误" + (remaining < 5 ? "，剩余尝试次数: " + remaining : ""));
        }

        if (user.getStatus() != SysUser.UserStatus.ACTIVE) {
            // V2.0.26 P2.4：账号已停用 → 记录安全审计事件
            authAuditService.recordFailure(
                    AuthAuditEvent.EventType.LOGIN_DISABLED,
                    user.getId(), user.getUsername(), ip,
                    "/api/auth/login", "POST",
                    "ACCOUNT_DISABLED", "账号已停用，无法登录");
            throw new IllegalArgumentException("账号已停用");
        }

        // ═══ V2.0.42：2FA 登录 challenge（密码正确后、会话建立前校验 TOTP）═══
        // 设计：不暴露"是否启用了 2FA"给未认证方——challenge 失败与密码错误统一 401 文案不同码
        if (twoFactorService.isEnabled(user.getId())) {
            String totpCode = req.getTotpCode();
            boolean totpOk = false;
            String failReason = null;
            if (totpCode == null || totpCode.isBlank()) {
                failReason = "MFA_REQUIRED";
            } else {
                totpOk = twoFactorService.verifyLoginCode(user.getId(), totpCode.trim());
                if (!totpOk) failReason = "MFA_INVALID";
            }
            if (!totpOk) {
                // 密码已对但 2FA 未过：不计入 Bug-09 失败锁定（防锁死），但记安全审计
                authAuditService.recordFailure(
                        AuthAuditEvent.EventType.LOGIN_FAIL,
                        user.getId(), user.getUsername(), ip,
                        "/api/auth/login", "POST",
                        failReason,
                        "MFA_REQUIRED".equals(failReason) ? "已启用 2FA，请携带验证码登录" : "2FA 验证码错误");
                // V2.0.65：MFA 挑战失败同样回填公司归属（company-admin 审计 tab 可见）
                auditLogService.recordWithSubject("LOGIN_FAIL", "USER",
                        user.getId(), user.getUsername(),
                        "MFA_REQUIRED".equals(failReason)
                                ? "2FA 挑战（缺少验证码）: " + user.getUsername()
                                : "2FA 挑战失败（验证码错误）: " + user.getUsername());
                LoginResponse challenge = new LoginResponse();
                challenge.setMfaRequired(true);
                challenge.setMfaReason(failReason);
                return challenge;   // HTTP 200 + mfa_required=true（前端弹验证码输入）
            }
        }

        // 成功清零
        attemptService.recordSuccess(req.getUsername());

        // 解析用户的多公司关系
        List<CompanyUser> memberships = companyUserRepo.findByUserId(user.getId()).stream()
                .filter(cu -> cu.getStatus() == CompanyUser.Status.ACTIVE)
                .toList();
        Long currentCompanyId = memberships.isEmpty() ? null : memberships.get(0).getCompanyId();

        // V2.0.10：公司状态 EXPIRED 拦截（非平台超管）
        if (!Boolean.TRUE.equals(user.getIsPlatformAdmin()) && currentCompanyId != null) {
            Company company = companyRepo.findById(currentCompanyId).orElse(null);
            if (company != null && company.getStatus() == Company.CompanyStatus.EXPIRED) {
                throw new IllegalArgumentException(
                        "公司「" + company.getName() + "」许可证已过期，登录被拦截。请联系大超管续期或恢复状态。");
            }
        }

        // Phase 2 准备：从 Company 和 CompanyUser 加载 displayName/companyName（用于标准化断言）
        String companyCode = null;
        String companyName = null;
        if (currentCompanyId != null) {
            Company company = companyRepo.findById(currentCompanyId).orElse(null);
            if (company != null) {
                companyCode = company.getCode();
                companyName = company.getName();
            }
        }
        String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getUsername();

        // 加载产品授权
        List<LoginResponse.ProductView> products = buildProductView(user.getId(), currentCompanyId, user.getIsPlatformAdmin());

        // === V2.0.16 W1 计算 JWT claims 平铺字段 ===

        // is_tenant_admin: 平台超管 或 该用户在当前租户的 CompanyUser.role = SUPER_ADMIN
        boolean isPlatformAdminFlag = user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin();
        boolean isTenantAdmin = isPlatformAdminFlag || memberships.stream()
                .anyMatch(cu -> cu.getRole() == CompanyUser.CompanyRole.SUPER_ADMIN);

        // sub_product_grants: 当前用户已授权的子产品 code 列表（licensed=true）
        List<String> subProductGrants = products.stream()
                .filter(LoginResponse.ProductView::isLicensed)
                .map(LoginResponse.ProductView::getCode)
                .toList();

        // 颁发 JWT — V2.0.16 W1（11 参 + 平铺字段 is_tenant_admin / sub_product_grants / tenant_name）
        String token = jwtUtil.generate(
                user.getId(),
                user.getUsername(),
                currentCompanyId,
                companyCode,
                isPlatformAdminFlag,
                null,           // productCode: 主网站登录时不绑定具体产品，由 /enter 端点注入
                null,           // roleCode: 主网站不再持有产品内角色
                displayName,    // Phase 2 新增
                companyName,    // Phase 2 新增
                user.getUsername(), // Phase 2 新增：授权人
                isTenantAdmin,  // V2.0.16 W1 新增
                subProductGrants // V2.0.16 W1 新增
        );

        // 设置 CurrentUser（供后续 Service 使用）— Phase 2 解耦版
        CurrentUser cu = new CurrentUser();
        cu.setUserId(user.getId());
        cu.setUsername(user.getUsername());
        cu.setCompanyId(currentCompanyId);
        cu.setCompanyCode(companyCode);
        cu.setPlatformAdmin(user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin());
        cu.setTenantName(companyName);
        cu.setDisplayName(displayName);
        CurrentUser.set(cu);

        // 审计
        // V2.0.63：显式回填公司归属（同 LOGIN_FAIL）
        auditLogService.recordWithSubject("LOGIN_SUCCESS", "USER", user.getId(), user.getUsername(),
                "登录成功: " + user.getUsername());
        // V2.0.26 P2.4：登录成功 → 记录安全审计事件
        authAuditService.recordSuccess(
                AuthAuditEvent.EventType.LOGIN_SUCCESS,
                user.getId(), user.getUsername(), ip,
                "/api/auth/login", "POST");

        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setExpiresIn(jwtUtil.getAccessTokenTtlSeconds());

        // V2.0.21 P1.4 阶段 B：颁发 refresh_token（30 天有效，存 SHA-256 hash）
        String refreshPlain = JwtUtil.generateRefreshTokenPlain();
        String refreshHash = JwtUtil.hashRefreshToken(refreshPlain);
        LocalDateTime refreshExpiresAt = LocalDateTime.now().plusSeconds(JwtUtil.REFRESH_TOKEN_TTL_SECONDS);
        RefreshToken refreshToken = new RefreshToken(user.getId(), refreshHash, refreshExpiresAt, ip, null);
        refreshTokenRepository.save(refreshToken);

        resp.setRefreshToken(refreshPlain);
        resp.setRefreshTokenExpiresIn(JwtUtil.REFRESH_TOKEN_TTL_SECONDS);

        LoginResponse.UserView uv = new LoginResponse.UserView();
        uv.setId(user.getId());
        uv.setUsername(user.getUsername());
        uv.setDisplayName(user.getDisplayName());
        uv.setEmail(user.getEmail());
        uv.setPlatformAdmin(Boolean.TRUE.equals(user.getIsPlatformAdmin()));
        // === V2.0.16 W1/W4 新增：与 JWT 平铺字段 is_tenant_admin 保持一致 ===
        uv.setTenantAdmin(isTenantAdmin);
        resp.setUser(uv);

        if (currentCompanyId != null) {
            companyRepo.findById(currentCompanyId).ifPresent(c -> {
                LoginResponse.CompanyView cv = new LoginResponse.CompanyView();
                cv.setId(c.getId());
                cv.setCode(c.getCode());
                cv.setName(c.getName());
                resp.setCompany(cv);
            });
        }

        resp.setProducts(products);

        // P0-A：私有化首登强制改密（仅当前用户 passwordChanged=false 时返回 true）
        boolean mustChange = Boolean.FALSE.equals(user.getPasswordChanged());
        resp.setMustChangePassword(mustChange);
        if (mustChange) {
            auditLogService.recordWithSubject("LOGIN_MUST_CHANGE_PASSWORD", "USER", user.getId(), user.getUsername(),
                    "首登触发强制改密: " + user.getUsername());
        }

        return resp;
    }

    /**
     * P0-A：修改当前用户密码
     * @param userId       当前用户 ID
     * @param oldPassword  原密码（必须正确，否则抛 IllegalArgumentException）
     * @param newPassword  新密码（≥ 8 位，且不能与用户名相同）
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码长度至少 8 位");
        }
        SysUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("原密码错误");
        }
        if (newPassword.equals(user.getUsername())) {
            throw new IllegalArgumentException("新密码不能与用户名相同");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChanged(true);
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userRepo.save(user);
        attemptService.recordSuccess(user.getUsername());

        // V2.0.21 P1.4 阶段 B：改密后撤销所有 active refresh_token（强制其他设备重新登录）
        LocalDateTime now = LocalDateTime.now();
        List<RefreshToken> activeRefreshTokens = refreshTokenRepository.findActiveByUserId(userId, now);
        for (RefreshToken rt : activeRefreshTokens) {
            rt.setRevokedAt(now);
            rt.setRevokedReason("PASSWORD_CHANGED");
        }
        refreshTokenRepository.saveAll(activeRefreshTokens);

        auditLogService.recordWithSubject("PASSWORD_CHANGED", "USER", user.getId(), user.getUsername(),
                "用户修改密码成功: " + user.getUsername() + " (revokedRefreshTokens=" + activeRefreshTokens.size() + ")");

        // V2.0.26 P2.4：主动改密 → 记录安全审计事件
        authAuditService.recordSuccess(
                AuthAuditEvent.EventType.PASSWORD_CHANGED,
                user.getId(), user.getUsername(), null,
                "/api/auth/change-password", "POST");
    }

    // ═══════════════════════ V2.0.20 P1 阶段 A 新增：logout + getMe ═══════════════════════

    /**
     * P1.2 logout：把当前 jti 写入黑名单，JwtAuthFilter 校验时拒绝
     *
     * @param userId  当前用户 ID（用于审计）
     * @param claims  解析后的 Claims（含 jti + exp）
     * @param ip      客户端 IP（用于审计）
     */
    @Transactional
    public void logout(Long userId, Claims claims, String ip) {
        String jti = claims.getId();
        Date exp = claims.getExpiration();
        if (jti == null || exp == null) {
            // 老 token 没 jti/exp（理论上不应该出现），静默放过
            return;
        }
        // 幂等：已黑名单的不重复插入（jti 唯一约束保护）
        if (tokenBlacklistRepository.existsByJti(jti)) {
            return;
        }
        LocalDateTime expiresAt = LocalDateTime.ofInstant(exp.toInstant(), ZoneId.systemDefault());
        TokenBlacklist entry = new TokenBlacklist(
                jti, userId, null, expiresAt, "LOGOUT", ip);
        tokenBlacklistRepository.save(entry);

        // V2.0.21 P1.4 阶段 B：撤销该用户的所有 refresh_token（强制重新登录）
        LocalDateTime now = LocalDateTime.now();
        List<RefreshToken> activeRefreshTokens = refreshTokenRepository.findActiveByUserId(userId, now);
        for (RefreshToken rt : activeRefreshTokens) {
            rt.setRevokedAt(now);
            rt.setRevokedReason("LOGOUT");
        }
        refreshTokenRepository.saveAll(activeRefreshTokens);

        auditLogService.recordWithSubject("LOGOUT", "USER", userId, null,
                "用户主动退出登录 (jti=" + jti + ", refreshTokens=" + activeRefreshTokens.size() + ")");

        // V2.0.26 P2.4：登出 → 记录安全审计事件
        authAuditService.recordSuccess(
                AuthAuditEvent.EventType.LOGOUT,
                userId, claims.getSubject(), ip,
                "/api/auth/logout", "POST");
    }

    /**
     * P1.1 /me：返回当前用户的 profile + company + products
     *
     * 用于前端刷新页面 / token 即将过期时校验身份信息
     * 不返回密码 hash 等敏感字段
     *
     * @param userId  当前用户 ID
     * @return LoginResponse（token / expiresIn 字段填充为占位）
     */
    public LoginResponse getMe(Long userId) {
        SysUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));

        // 解析多公司
        List<CompanyUser> memberships = companyUserRepo.findByUserId(userId).stream()
                .filter(cu -> cu.getStatus() == CompanyUser.Status.ACTIVE)
                .toList();
        Long currentCompanyId = memberships.isEmpty() ? null : memberships.get(0).getCompanyId();

        String companyCode = null;
        String companyName = null;
        if (currentCompanyId != null) {
            Company company = companyRepo.findById(currentCompanyId).orElse(null);
            if (company != null) {
                companyCode = company.getCode();
                companyName = company.getName();
            }
        }

        String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getUsername();
        boolean isPlatformAdminFlag = Boolean.TRUE.equals(user.getIsPlatformAdmin());
        boolean isTenantAdmin = isPlatformAdminFlag || memberships.stream()
                .anyMatch(cu -> cu.getRole() == CompanyUser.CompanyRole.SUPER_ADMIN);

        List<LoginResponse.ProductView> products = buildProductView(userId, currentCompanyId, isPlatformAdminFlag);

        LoginResponse resp = new LoginResponse();
        // /me 不重发 token，前端继续用旧 token
        resp.setToken(null);
        resp.setExpiresIn(jwtUtil.getExpirationSeconds());

        LoginResponse.UserView uv = new LoginResponse.UserView();
        uv.setId(user.getId());
        uv.setUsername(user.getUsername());
        uv.setDisplayName(user.getDisplayName());
        uv.setEmail(user.getEmail());
        uv.setPlatformAdmin(isPlatformAdminFlag);
        uv.setTenantAdmin(isTenantAdmin);
        resp.setUser(uv);

        if (currentCompanyId != null) {
            final String fCompanyCode = companyCode;
            final String fCompanyName = companyName;
            companyRepo.findById(currentCompanyId).ifPresent(c -> {
                LoginResponse.CompanyView cv = new LoginResponse.CompanyView();
                cv.setId(c.getId());
                cv.setCode(c.getCode());
                cv.setName(c.getName());
                resp.setCompany(cv);
            });
        }

        resp.setProducts(products);
        resp.setMustChangePassword(Boolean.FALSE.equals(user.getPasswordChanged()));

        return resp;
    }

    // ═══════════════════════ V2.0.21 P1.4 阶段 B：refresh_token 滑动续期 ═══════════════════════

    /**
     * P1.4 refresh：拿 refresh_token 换新 access_token + 新 refresh_token
     *
     * 滑动续期策略：
     * 1) 校验 refresh_token：未过期 + 未使用 + 未撤销
     * 2) 用 hash 查 DB → 命中
     * 3) 标 used_at = now() + replaced_by_id = 新 token id（滑动链追踪）
     * 4) 颁发新 access_token（24h）+ 新 refresh_token（30d）
     *
     * 防重放：旧 token 再次出现 → 整条链全部撤销（refresh_replay_detected）
     *
     * @param refreshPlain  客户端传来的 refresh_token 明文（URL-safe Base64）
     * @param ip            客户端 IP
     * @return LoginResponse（含新 access_token + 新 refresh_token）
     * @throws IllegalArgumentException 各种非法 refresh_token 情况
     */
    @Transactional
    public LoginResponse refresh(String refreshPlain, String ip) {
        if (refreshPlain == null || refreshPlain.isBlank()) {
            throw new IllegalArgumentException("refresh_token 不能为空");
        }
        String hash = JwtUtil.hashRefreshToken(refreshPlain);
        RefreshToken oldToken = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> {
                    // V2.0.26 P2.4：无效 refresh_token → 记录安全审计事件
                    authAuditService.recordFailure(
                            AuthAuditEvent.EventType.TOKEN_REFRESH_INVALID,
                            null, null, ip,
                            "/api/auth/refresh", "POST",
                            "INVALID_REFRESH_TOKEN", "refresh_token hash 不存在（伪造或已过期清理）");
                    return new IllegalArgumentException("refresh_token 无效");
                });

        // ★ 防重放检测：已 used 的 token 再次出现 → 整条链撤销
        if (oldToken.isUsed()) {
            // 找出同用户的所有 active refresh_token，全部撤销
            LocalDateTime now = LocalDateTime.now();
            List<RefreshToken> chainTokens = refreshTokenRepository.findActiveByUserId(oldToken.getUserId(), now);
            for (RefreshToken t : chainTokens) {
                t.setRevokedAt(now);
                t.setRevokedReason("REFRESH_REPLAY_DETECTED");
            }
            refreshTokenRepository.saveAll(chainTokens);
            auditLogService.recordWithSubject("REFRESH_REPLAY_DETECTED", "USER", oldToken.getUserId(), null,
                    "refresh_token 重放检测：已 used 的 token 再次出现，已撤销用户所有 active refresh_token (chainSize=" + chainTokens.size() + ")");
            // V2.0.26 P2.4：refresh_token 重放 → 记录安全审计事件（关键攻击信号）
            authAuditService.recordFailureWithMeta(
                    AuthAuditEvent.EventType.TOKEN_REFRESH_REPLAY,
                    oldToken.getUserId(), null, ip,
                    "/api/auth/refresh", "POST",
                    "REFRESH_REPLAY_DETECTED",
                    "已 used 的 refresh_token 再次出现，已撤销用户所有会话",
                    java.util.Map.of("oldTokenId", oldToken.getId(), "revokedTokensCount", chainTokens.size()));
            throw new IllegalArgumentException("refresh_token 已失效（疑似重放攻击，已撤销该用户所有会话，请重新登录）");
        }

        if (oldToken.isRevoked()) {
            authAuditService.recordFailure(
                    AuthAuditEvent.EventType.TOKEN_REFRESH_INVALID,
                    oldToken.getUserId(), null, ip,
                    "/api/auth/refresh", "POST",
                    "REVOKED", "refresh_token 已撤销（" + oldToken.getRevokedReason() + "）");
            throw new IllegalArgumentException("refresh_token 已撤销（" + oldToken.getRevokedReason() + "）");
        }
        if (oldToken.isExpired()) {
            authAuditService.recordFailure(
                    AuthAuditEvent.EventType.TOKEN_REFRESH_INVALID,
                    oldToken.getUserId(), null, ip,
                    "/api/auth/refresh", "POST",
                    "EXPIRED", "refresh_token 已过期");
            throw new IllegalArgumentException("refresh_token 已过期，请重新登录");
        }

        // ── 1) 标记旧 token 为 used ──
        LocalDateTime now = LocalDateTime.now();
        oldToken.setUsedAt(now);

        // ── 2) 颁发新 refresh_token ──
        String newPlain = JwtUtil.generateRefreshTokenPlain();
        String newHash = JwtUtil.hashRefreshToken(newPlain);
        LocalDateTime newExpiresAt = now.plusSeconds(JwtUtil.REFRESH_TOKEN_TTL_SECONDS);
        RefreshToken newRefresh = new RefreshToken(oldToken.getUserId(), newHash, newExpiresAt, ip, null);
        newRefresh = refreshTokenRepository.save(newRefresh);

        // ── 3) 滑动链追踪 ──
        oldToken.setReplacedById(newRefresh.getId());
        refreshTokenRepository.save(oldToken);

        // ── 4) 颁发新 access_token ──
        // 复用 login() 的逻辑：构造 UserView + CompanyView + Products
        SysUser user = userRepo.findById(oldToken.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + oldToken.getUserId()));

        // 用户多公司关系
        List<CompanyUser> memberships = companyUserRepo.findByUserId(user.getId()).stream()
                .filter(cu -> cu.getStatus() == CompanyUser.Status.ACTIVE)
                .toList();
        Long currentCompanyId = memberships.isEmpty() ? null : memberships.get(0).getCompanyId();

        String companyCode = null;
        String companyName = null;
        if (currentCompanyId != null) {
            Company company = companyRepo.findById(currentCompanyId).orElse(null);
            if (company != null) {
                companyCode = company.getCode();
                companyName = company.getName();
            }
        }
        String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getUsername();
        boolean isPlatformAdminFlag = Boolean.TRUE.equals(user.getIsPlatformAdmin());
        boolean isTenantAdmin = isPlatformAdminFlag || memberships.stream()
                .anyMatch(cu -> cu.getRole() == CompanyUser.CompanyRole.SUPER_ADMIN);
        List<String> subProductGrants = buildProductView(user.getId(), currentCompanyId, isPlatformAdminFlag).stream()
                .filter(LoginResponse.ProductView::isLicensed)
                .map(LoginResponse.ProductView::getCode)
                .toList();

        String accessToken = jwtUtil.generate(
                user.getId(),
                user.getUsername(),
                currentCompanyId,
                companyCode,
                isPlatformAdminFlag,
                null, null,
                displayName, companyName, user.getUsername(),
                isTenantAdmin, subProductGrants
        );

        // ── 5) 组装响应（不重发 mustChangePassword，前端不动）──
        LoginResponse resp = new LoginResponse();
        resp.setToken(accessToken);
        resp.setExpiresIn(jwtUtil.getAccessTokenTtlSeconds());
        resp.setRefreshToken(newPlain);
        resp.setRefreshTokenExpiresIn(JwtUtil.REFRESH_TOKEN_TTL_SECONDS);

        LoginResponse.UserView uv = new LoginResponse.UserView();
        uv.setId(user.getId());
        uv.setUsername(user.getUsername());
        uv.setDisplayName(user.getDisplayName());
        uv.setEmail(user.getEmail());
        uv.setPlatformAdmin(isPlatformAdminFlag);
        uv.setTenantAdmin(isTenantAdmin);
        resp.setUser(uv);

        if (currentCompanyId != null) {
            final String fCompanyCode = companyCode;
            final String fCompanyName = companyName;
            companyRepo.findById(currentCompanyId).ifPresent(c -> {
                LoginResponse.CompanyView cv = new LoginResponse.CompanyView();
                cv.setId(c.getId());
                cv.setCode(c.getCode());
                cv.setName(c.getName());
                resp.setCompany(cv);
            });
        }

        resp.setProducts(buildProductView(user.getId(), currentCompanyId, isPlatformAdminFlag));
        resp.setMustChangePassword(false);   // refresh 不应该触发强制改密检查

        auditLogService.recordWithSubject("TOKEN_REFRESH", "USER", user.getId(), user.getUsername(),
                "refresh_token 滑动续期成功 (oldTokenId=" + oldToken.getId() + " → newTokenId=" + newRefresh.getId() + ")");

        // V2.0.26 P2.4：refresh_token 续期成功 → 记录安全审计事件
        authAuditService.recordSuccess(
                AuthAuditEvent.EventType.TOKEN_REFRESH,
                user.getId(), user.getUsername(), ip,
                "/api/auth/refresh", "POST");

        return resp;
    }

    // ══════════════════════════════════════════════════════════════════
    // V2.0.21 P1.5 阶段 B：forgot-password（邮件重置密码）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 默认 reset_token TTL：1 小时
     */
    public static final long RESET_TOKEN_TTL_SECONDS = 3600L;

    /**
     * 防 spam：同一用户 1 分钟内只允许申请 1 次（如果最近已有未用 reset token，直接返回旧的）
     */
    public static final long RESET_TOKEN_DEBOUNCE_SECONDS = 60L;

    /**
     * P1.5 forgot-password：提交 email/username，返回 ok
     *
     * 安全策略：
     * - 始终返回 200（无论邮箱是否存在），防止用户枚举攻击
     * - 防 spam：1 分钟内重复请求同一邮箱 → 直接返回旧的 token（避免邮件轰炸）
     * - token 仅在 API 返回中回显（不进 audit / 日志），仅 DB 存 SHA-256 hash
     *
     * @param emailOrUsername 邮箱或用户名（系统统一识别）
     * @param ip              客户端 IP
     * @return Map { success, message, token?: "..." }  token 仅在邮件通道未配置时返回（开发模式）
     */
    @Transactional
    public Map<String, Object> forgotPassword(String emailOrUsername, String ip) {
        if (emailOrUsername == null || emailOrUsername.isBlank()) {
            throw new IllegalArgumentException("邮箱或用户名不能为空");
        }

        // 1) 查用户（邮箱或用户名都行）
        SysUser user = userRepo.findByEmail(emailOrUsername)
                .or(() -> userRepo.findByUsername(emailOrUsername))
                .orElse(null);

        // ★ 安全：无论用户是否存在，都返回 200
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", "如果该邮箱已注册，重置链接已发送到该邮箱，请查收（含垃圾邮件夹）");

        if (user == null) {
            // 用户不存在 → 记录 audit（可疑枚举行为）但不泄漏信息
            auditLogService.recordWithSubject("FORGOT_PASSWORD_USER_NOT_FOUND", "USER", null, null,
                    "forgot-password 请求的用户名/邮箱不存在: " + emailOrUsername + " (ip=" + ip + ")");
            // V2.0.26 P2.4：枚举尝试 → 记录安全审计事件
            authAuditService.recordFailure(
                    AuthAuditEvent.EventType.PASSWORD_RESET_USER_NOT_FOUND,
                    null, emailOrUsername, ip,
                    "/api/auth/forgot-password", "POST",
                    "USER_NOT_FOUND", "forgot-password 请求的用户名/邮箱不存在（可疑枚举）");
            return resp;
        }

        // 2) 防 spam：检查最近 1 分钟是否已有未用的 reset token
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime debounceCutoff = now.minusSeconds(RESET_TOKEN_DEBOUNCE_SECONDS);
        java.util.List<PasswordResetToken> recentTokens = passwordResetTokenRepository.findActiveByUserId(user.getId(), now);
        PasswordResetToken existing = recentTokens.isEmpty() ? null : recentTokens.get(0);

        String plainToken;
        if (existing != null && existing.getCreatedAt().isAfter(debounceCutoff)) {
            // 1 分钟内已发过 → 复用旧 token（不重新生成，避免邮件轰炸）
            plainToken = "[已发送，请查看邮箱]";   // 不回显明文 token
        } else {
            // 生成新 token
            plainToken = JwtUtil.generateRefreshTokenPlain();   // 复用 SecureRandom 工具（32B Base64）
            String tokenHash = JwtUtil.hashRefreshToken(plainToken);
            LocalDateTime expiresAt = now.plusSeconds(RESET_TOKEN_TTL_SECONDS);
            PasswordResetToken tokenEntity = new PasswordResetToken(user.getId(), tokenHash, expiresAt, ip);
            passwordResetTokenRepository.save(tokenEntity);

            auditLogService.recordWithSubject("PASSWORD_RESET_REQUESTED", "USER", user.getId(), user.getUsername(),
                    "forgot-password 请求成功，邮件已发送 (ip=" + ip + ", email=" + maskEmail(user.getEmail()) + ")");

            // V2.0.26 P2.4：忘记密码请求 → 记录安全审计事件
            authAuditService.recordSuccess(
                    AuthAuditEvent.EventType.PASSWORD_RESET_REQUESTED,
                    user.getId(), user.getUsername(), ip,
                    "/api/auth/forgot-password", "POST");

            // 开发模式：邮件通道未配置时直接返回 token 给前端（V2.0.21 仅本地开发使用）
            // 生产环境邮件通道启用后，应该删除这行
            resp.put("devModeToken", plainToken);
            resp.put("resetUrl", "/reset-password.html?token=" + plainToken);
            resp.put("expiresIn", RESET_TOKEN_TTL_SECONDS);
        }

        return resp;
    }

    /**
     * 邮箱脱敏：admin@lingyao.cn → a***n@lingyao.cn
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf("@");
        if (at <= 1) return "***" + email.substring(at);
        return email.charAt(0) + "***" + email.charAt(at - 1) + email.substring(at);
    }

    /**
     * P1.5 reset-password：用 reset_token 重置密码
     *
     * 安全策略：
     * - token 必须 hash 命中 DB + 未过期 + 未使用
     * - 新密码 ≥ 8 位 + 不能与 username 相同（同 changePassword）
     * - 重置成功后撤销该用户所有 active refresh_token（强制其他设备重新登录）
     * - 重置成功后清空 login attempts（防旧密码被锁定）
     *
     * @param tokenPlain  前端从邮件链接拿到的 token 明文
     * @param newPassword 新密码（≥ 8 位）
     * @param ip          客户端 IP
     */
    @Transactional
    public void resetPassword(String tokenPlain, String newPassword, String ip) {
        if (tokenPlain == null || tokenPlain.isBlank()) {
            throw new IllegalArgumentException("reset_token 不能为空");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码长度至少 8 位");
        }
        String hash = JwtUtil.hashRefreshToken(tokenPlain);
        PasswordResetToken tokenEntity = passwordResetTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> {
                    // V2.0.26 P2.4：无效 reset_token → 记录安全审计事件
                    authAuditService.recordFailure(
                            AuthAuditEvent.EventType.PASSWORD_RESET_INVALID,
                            null, null, ip,
                            "/api/auth/reset-password", "POST",
                            "INVALID_RESET_TOKEN", "reset_token hash 不存在（伪造或已清理）");
                    return new IllegalArgumentException("reset_token 无效");
                });
        if (tokenEntity.isUsed()) {
            authAuditService.recordFailure(
                    AuthAuditEvent.EventType.PASSWORD_RESET_INVALID,
                    tokenEntity.getUserId(), null, ip,
                    "/api/auth/reset-password", "POST",
                    "RESET_TOKEN_USED", "reset_token 已被使用");
            throw new IllegalArgumentException("reset_token 已使用");
        }
        if (tokenEntity.isExpired()) {
            authAuditService.recordFailure(
                    AuthAuditEvent.EventType.PASSWORD_RESET_INVALID,
                    tokenEntity.getUserId(), null, ip,
                    "/api/auth/reset-password", "POST",
                    "RESET_TOKEN_EXPIRED", "reset_token 已过期");
            throw new IllegalArgumentException("reset_token 已过期，请重新申请");
        }

        SysUser user = userRepo.findById(tokenEntity.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + tokenEntity.getUserId()));

        if (newPassword.equals(user.getUsername())) {
            throw new IllegalArgumentException("新密码不能与用户名相同");
        }

        // 1) 修改密码
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChanged(true);
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.save(user);

        // 2) 标 token 为 used
        tokenEntity.setUsedAt(LocalDateTime.now());
        passwordResetTokenRepository.save(tokenEntity);

        // 3) 撤销该用户所有 active refresh_token（强制其他设备重新登录）
        LocalDateTime now = LocalDateTime.now();
        List<RefreshToken> activeRefreshTokens = refreshTokenRepository.findActiveByUserId(user.getId(), now);
        for (RefreshToken rt : activeRefreshTokens) {
            rt.setRevokedAt(now);
            rt.setRevokedReason("PASSWORD_CHANGED");
        }
        refreshTokenRepository.saveAll(activeRefreshTokens);

        // 4) 清登录失败次数（防旧密码被锁的二次伤害）
        attemptService.recordSuccess(user.getUsername());

        auditLogService.recordWithSubject("PASSWORD_RESET", "USER", user.getId(), user.getUsername(),
                "用户通过 reset_token 重置密码成功 (revokedRefreshTokens=" + activeRefreshTokens.size() + ", ip=" + ip + ")");

        // V2.0.26 P2.4：重置密码成功 → 记录安全审计事件
        authAuditService.recordSuccess(
                AuthAuditEvent.EventType.PASSWORD_RESET,
                user.getId(), user.getUsername(), ip,
                "/api/auth/reset-password", "POST");
    }

    private List<LoginResponse.ProductView> buildProductView(Long userId, Long companyId, Boolean isPlatformAdmin) {
        // V2.0.54：产品目录收敛（主人拍板）——只下发 ACTIVE 产品（棱镜/皓元/源策/医呈），
        // DEPRECATED（辰录/明枢）不再出现在 me/登录/工作台/JWT claim 中（软下架，历史授权保留可恢复）
        List<Product> allProducts = productRepo.findByStatusOrderBySortOrderAsc(Product.Status.ACTIVE);
        // V2.0.50：licensed/granted 一律按「公司真实授权 + 用户个人授权」口径，
        // 平台超管不再短路放行（此前 5 产品全显已开通，与 KPI 已开通数矛盾）。
        // 平台超管的进入能力由 /api/sub/{code}/enter 服务端鉴权（isPlatformAdmin 直通）保证，不依赖本视图。
        Set<Long> companyScope = companyId == null ? Set.of() : companyLicensedProductIds(companyId);
        Set<Long> userScope = grantRepo.findByUserIdAndStatus(userId, ProductUserGrant.Status.ACTIVE).stream()
                .map(ProductUserGrant::getProductId)
                .collect(Collectors.toSet());
        Map<Long, String> userRoleByProductId = userRoleRepo.findByUserId(userId).stream()
                .collect(Collectors.toMap(ProductUserRole::getProductId, ProductUserRole::getRoleCode, (a, b) -> a));

        List<LoginResponse.ProductView> result = new ArrayList<>();
        for (Product p : allProducts) {
            LoginResponse.ProductView pv = new LoginResponse.ProductView();
            pv.setId(p.getId());
            pv.setCode(p.getCode());
            pv.setName(p.getName());
            pv.setIcon(p.getIcon());
            boolean licensed = companyScope.contains(p.getId());
            boolean granted = licensed && userScope.contains(p.getId());
            pv.setLicensed(licensed);
            pv.setGranted(granted);
            String roleCode = userRoleByProductId.get(p.getId());
            pv.setRoleCode(roleCode);
            if (roleCode != null) {
                pv.setRoleName(roleRepo.findByProductIdAndRoleCode(p.getId(), roleCode)
                        .map(ProductRole::getRoleName).orElse(null));
            }
            pv.setEntryPath("/subtask/" + p.getCode());
            result.add(pv);
        }
        return result;
    }

    /** V2.0.50：公司级 ACTIVE 产品授权集合（提取方法便于空公司守卫） */
    private Set<Long> companyLicensedProductIds(Long companyId) {
        return companyProductRepo.findByCompanyIdAndStatus(companyId, CompanyProduct.Status.ACTIVE).stream()
                .map(CompanyProduct::getProductId)
                .collect(Collectors.toSet());
    }
}
