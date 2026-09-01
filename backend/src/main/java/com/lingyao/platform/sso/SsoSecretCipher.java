package com.lingyao.platform.sso;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * SSO app_secret 加密器（AES-256-GCM）
 *
 * 生产环境可替换为 AWS KMS / 阿里云 KMS / HashiCorp Vault 实现同一接口：
 * - encrypt(plain) → cipher
 * - decrypt(cipher) → plain
 *
 * 当前实现：用 Spring env 变量 LINGYAO_SSO_SECRET_KEY 作为密钥（Base64 编码的 32 字节）
 * 未配置时使用 dev 默认密钥（仅本地/dev 环境用）
 */
@Component
public class SsoSecretCipher {

    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 默认 dev 密钥（32 字节 Base64），仅本地开发用。生产必须设置 LINGYAO_SSO_SECRET_KEY */
    private static final String DEFAULT_DEV_KEY = "ZGV2X2Rldl9kZXZfZGV2X2Rldl9kZXZfZGV2X2Rldl8="; // "dev_dev_dev_dev_dev_dev_dev_dev_" base64

    private final SecretKey key;

    public SsoSecretCipher(@Value("${lingyao.sso.secret-key:}") String secretKeyBase64) {
        String effective = (secretKeyBase64 == null || secretKeyBase64.isBlank()) ? DEFAULT_DEV_KEY : secretKeyBase64;
        byte[] keyBytes = Base64.getDecoder().decode(effective);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("LINGYAO_SSO_SECRET_KEY 必须 ≥ 32 字节（当前 " + keyBytes.length + "）");
        }
        // 取前 32 字节
        byte[] key32 = new byte[32];
        System.arraycopy(keyBytes, 0, key32, 0, 32);
        this.key = new SecretKeySpec(key32, "AES");
    }

    /**
     * 加密明文 → Base64 密文（格式：iv(12B) + ciphertext+tag）
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] output = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(ciphertext, 0, output, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (Exception e) {
            throw new RuntimeException("SSO secret 加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密 Base64 密文 → 明文
     */
    public String decrypt(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isEmpty()) return cipherBase64;
        try {
            byte[] input = Base64.getDecoder().decode(cipherBase64);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(input, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[input.length - IV_LENGTH];
            System.arraycopy(input, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("SSO secret 解密失败（密文可能被篡改或密钥已轮换）: " + e.getMessage(), e);
        }
    }
}