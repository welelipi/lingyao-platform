package com.lingyao.platform.service;

import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.entity.User2FA;
import com.lingyao.platform.repository.SysUserRepository;
import com.lingyao.platform.repository.User2FARepository;
import com.lingyao.platform.sso.SsoSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 双因素认证服务 — V2.0.42 P2.4 · TOTP 真实现（RFC 6238 HMAC-SHA1，零新依赖）
 *
 * 实现（替代 V2.0.26 skeleton）：
 * 1) generateSetup(userId) → otpauth:// URI + Base32 secret（明文仅此一次回显，AES-256-GCM 入库）
 * 2) verifySetup(userId, code) → 首次 6 位 code 验证，通过后 enabled=true + 发 8 个 backup codes
 * 3) verifyLoginCode(userId, code) → 登录第二步（±1 窗口容错 + 30s 重放防护）
 * 4) verifyBackupCode(userId, code) → 一次性备份码（SHA-256 命中即作废）
 * 5) disable(userId, password) → 密码二次确认后关闭
 * 6) regenerateBackupCodes(userId, password) → 重发 8 个新码
 *
 * 安全：
 * - secret 加密复用 SsoSecretCipher（AES-256-GCM）
 * - backup codes 只存 SHA-256，明文仅生成时回显一次
 * - last_used_at 防 30 秒窗口重放
 * - TOTP 校验窗口 ±1（±30s 时钟偏移容错）
 */
@Service
public class TwoFactorService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);
    private static final int TOTP_PERIOD_SECONDS = 30;
    private static final int TOTP_DIGITS = 6;
    private static final int WINDOW_TOLERANCE = 1;   // ±1 个 30s 窗口
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private User2FARepository user2FARepository;

    @Autowired
    private SysUserRepository userRepo;

    @Autowired
    private com.lingyao.platform.sso.SsoSecretCipher cipher;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ══════════════════════════════════════════════════════════════════
    // 状态查询
    // ══════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getStatus(Long userId) {
        Optional<User2FA> opt = user2FARepository.findByUserId(userId);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("userId", userId);
        status.put("exists", opt.isPresent());
        if (opt.isPresent()) {
            User2FA u2fa = opt.get();
            status.put("enabled", u2fa.getEnabled());
            status.put("verified", u2fa.getVerifiedAt() != null);
            status.put("verifiedAt", u2fa.getVerifiedAt());
            status.put("lastUsedAt", u2fa.getLastUsedAt());
            status.put("createdAt", u2fa.getCreatedAt());
        }
        status.put("implementationStatus", "ACTIVE");
        return status;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(Long userId) {
        return user2FARepository.existsByUserIdAndEnabledTrue(userId);
    }

    // ══════════════════════════════════════════════════════════════════
    // V2.0.42 真实实现
    // ══════════════════════════════════════════════════════════════════

    /**
     * 开启 2FA：生成 TOTP secret（明文仅此一次返回，加密入库但 enabled=false 待验证）
     */
    @Transactional
    public Map<String, Object> generateSetup(Long userId) {
        SysUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));

        // 20 字节随机 secret → Base32（160 bit，RFC 4228 推荐）
        byte[] secretBytes = new byte[20];
        RANDOM.nextBytes(secretBytes);
        String secretBase32 = base32Encode(secretBytes);

        User2FA u2fa = user2FARepository.findByUserId(userId).orElseGet(User2FA::new);
        u2fa.setUserId(userId);
        u2fa.setSecretEncrypted(cipher.encrypt(secretBase32));
        u2fa.setEnabled(false);          // 验证通过才启用
        u2fa.setVerifiedAt(null);
        user2FARepository.save(u2fa);

        String issuer = "Lingyao";
        String otpauth = "otpauth://totp/" + issuer + ":" + user.getUsername()
                + "?secret=" + secretBase32 + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=" + TOTP_DIGITS + "&period=" + TOTP_PERIOD_SECONDS;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("secret", secretBase32);        // 明文仅此一次
        data.put("otpauthUrl", otpauth);         // 前端可自行渲染 QR（任意 qrcode 库）
        data.put("username", user.getUsername());
        log.info("[2FA] 用户 {} 开始 2FA 设置（secret 已生成待验证）", user.getUsername());
        return data;
    }

    /**
     * 验证首次设置：6 位 code 正确 → enabled=true + 生成 8 个 backup codes（明文仅此一次）
     */
    @Transactional
    public Map<String, Object> verifySetup(Long userId, String code) {
        User2FA u2fa = user2FARepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("请先调用 setup 生成 2FA 配置"));
        if (Boolean.TRUE.equals(u2fa.getEnabled())) {
            throw new IllegalArgumentException("2FA 已启用，无需重复设置");
        }
        String secret = cipher.decrypt(u2fa.getSecretEncrypted());
        if (!verifyTotp(secret, code, u2fa.getLastUsedAt())) {
            throw new IllegalArgumentException("验证码错误，请确认 Authenticator 时间后重试");
        }
        u2fa.setEnabled(true);
        u2fa.setVerifiedAt(LocalDateTime.now());
        u2fa.setLastUsedAt(LocalDateTime.now());

        String[] plainCodes = generateBackupCodes();
        u2fa.setBackupCodesHash(hashBackupCodes(plainCodes));
        user2FARepository.save(u2fa);
        log.info("[2FA] 用户 {} 2FA 启用成功", userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", true);
        data.put("backupCodes", plainCodes);     // 明文仅此一次
        return data;
    }

    /**
     * 登录第二步：TOTP 6 位码或备份码均可
     */
    @Transactional
    public boolean verifyLoginCode(Long userId, String code) {
        User2FA u2fa = user2FARepository.findByUserId(userId).orElse(null);
        if (u2fa == null || !Boolean.TRUE.equals(u2fa.getEnabled())) return false;
        if (code == null || !code.matches("\\d{6}")) {
            return verifyBackupCode(userId, code);
        }
        String secret = cipher.decrypt(u2fa.getSecretEncrypted());
        boolean ok = verifyTotp(secret, code, u2fa.getLastUsedAt());
        if (ok) {
            u2fa.setLastUsedAt(LocalDateTime.now());
            user2FARepository.save(u2fa);
        }
        return ok;
    }

    /**
     * 一次性备份码验证（命中即作废）
     */
    @Transactional
    public boolean verifyBackupCode(Long userId, String code) {
        if (code == null || code.isBlank()) return false;
        User2FA u2fa = user2FARepository.findByUserId(userId).orElse(null);
        if (u2fa == null || u2fa.getBackupCodesHash() == null) return false;

        String normalized = code.trim().toUpperCase().replace("-", "");
        String target = sha256(normalized);
        List<String> hashes = parseBackupHashes(u2fa.getBackupCodesHash());
        if (!hashes.contains(target)) return false;

        hashes.remove(target);   // 一次性：命中即删
        u2fa.setBackupCodesHash(toJsonArray(hashes));
        user2FARepository.save(u2fa);
        log.info("[2FA] 用户 {} 使用备份码登录（剩余 {} 个）", userId, hashes.size());
        return true;
    }

    /**
     * 关闭 2FA（密码二次确认）
     */
    @Transactional
    public void disable(Long userId, String password) {
        if (!verifyPassword(userId, password)) {
            throw new IllegalArgumentException("密码错误，无法关闭 2FA");
        }
        User2FA u2fa = user2FARepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("该用户未设置 2FA"));
        user2FARepository.delete(u2fa);
        log.info("[2FA] 用户 {} 已关闭 2FA", userId);
    }

    /**
     * 重生成 8 个 backup codes（密码二次确认；明文仅此一次）
     */
    @Transactional
    public String[] regenerateBackupCodes(Long userId, String password) {
        if (!verifyPassword(userId, password)) {
            throw new IllegalArgumentException("密码错误");
        }
        User2FA u2fa = user2FARepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("该用户未设置 2FA"));
        String[] plainCodes = generateBackupCodes();
        u2fa.setBackupCodesHash(hashBackupCodes(plainCodes));
        user2FARepository.save(u2fa);
        return plainCodes;
    }

    /**
     * 验证用户密码（2FA 敏感操作二次确认）
     */
    public boolean verifyPassword(Long userId, String password) {
        SysUser user = userRepo.findById(userId).orElse(null);
        return user != null && password != null
                && passwordEncoder.matches(password, user.getPasswordHash());
    }

    // ══════════════════════════════════════════════════════════════════
    // TOTP 核心（RFC 6238 HMAC-SHA1）
    // ══════════════════════════════════════════════════════════════════

    /** 校验 6 位 TOTP；±1 窗口容错；lastUsed 防同窗口重放 */
    private boolean verifyTotp(String secretBase32, String code, LocalDateTime lastUsedAt) {
        if (code == null || !code.matches("\\d{" + TOTP_DIGITS + "}")) return false;
        byte[] key = base32Decode(secretBase32);
        long nowStep = System.currentTimeMillis() / 1000L / TOTP_PERIOD_SECONDS;
        // V2.0.42 修复：lastUsedAt 是系统本地时间（LocalDateTime.now()），必须用系统时区换算 epoch，
        // 不能用 ZoneOffset.UTC（GMT+8 下会偏差 960 个窗口，导致重放防护误杀全部有效窗口）
        long lastStep = -1;
        if (lastUsedAt != null) {
            lastStep = lastUsedAt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond() / TOTP_PERIOD_SECONDS;
        }
        for (int i = -WINDOW_TOLERANCE; i <= WINDOW_TOLERANCE; i++) {
            long step = nowStep + i;
            if (step <= lastStep) continue;   // 防 30s 重放（含过去窗口）
            String expected = totpAt(key, step);
            if (constantTimeEquals(expected, code)) return true;
        }
        return false;
    }

    /** RFC 6238：HMAC-SHA1 → 动态截断 → 6 位十进制 */
    private String totpAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int mod = 1;
            for (int i = 0; i < TOTP_DIGITS; i++) mod *= 10;
            return String.format("%0" + TOTP_DIGITS + "d", binary % mod);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    private String[] generateBackupCodes() {
        String[] codes = new String[8];
        for (int i = 0; i < 8; i++) {
            // 格式 XXXX-XXXX（8 位 Base32 字符）
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 8; j++) {
                if (j == 4) sb.append('-');
                sb.append(BASE32_ALPHABET.charAt(RANDOM.nextInt(32)));
            }
            codes[i] = sb.toString();
        }
        return codes;
    }

    private String hashBackupCodes(String[] codes) {
        List<String> hashes = new ArrayList<>();
        for (String c : codes) hashes.add(sha256(c.replace("-", "").toUpperCase()));
        return toJsonArray(hashes);
    }

    private List<String> parseBackupHashes(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        String body = json.replaceAll("[\\[\\]\"]", "");
        for (String s : body.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    private byte[] base32Decode(String s) {
        int buffer = 0, bits = 0;
        List<Byte> out = new ArrayList<>();
        for (char c : s.toUpperCase().toCharArray()) {
            int v = BASE32_ALPHABET.indexOf(c);
            if (v < 0) continue;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.add((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        byte[] result = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) result[i] = out.get(i);
        return result;
    }
}
