package com.dataocean.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Redis 敏感值加解密工具。
 * <p>
 * 使用 AES-256-GCM 对存入 Redis 的敏感数据（如数据源密码）进行加密，
 * 避免明文密码直接暴露在 Redis 中。
 * </p>
 * <p>
 * 密钥从配置文件读取（dataocean.redis.encrypt-key），长度须为 32 字节。
 * </p>
 *
 * @author dataocean
 */
@Slf4j
@Component
public class RedisValueEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final byte[] keyBytes;

    public RedisValueEncryptor(
            @Value("${dataocean.redis.encrypt-key:#{null}}") String encryptKey) {
        if (encryptKey != null && encryptKey.length() == 32) {
            this.keyBytes = encryptKey.getBytes(StandardCharsets.UTF_8);
        } else {
            // 降级：使用固定内部密钥（仅限开发环境）
            log.warn("dataocean.redis.encrypt-key 未配置或长度不为 32，使用默认开发密钥");
            this.keyBytes = "DataOcean@2024!DefaultDevKey32".getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 加密明文，返回 Base64 编码的密文（含 IV）。
     *
     * @param plainText 明文
     * @return Base64 编码的密文，失败时返回 null
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // IV + ciphertext(含 tag)
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            log.error("Redis 值加密失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解密 Base64 编码的密文，返回明文。
     *
     * @param encryptedText Base64 编码的密文
     * @return 明文，失败时返回 null
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        try {
            byte[] data = Base64.getDecoder().decode(encryptedText);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[data.length - GCM_IV_LENGTH];
            System.arraycopy(data, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Redis 值解密失败: {}", e.getMessage());
            return null;
        }
    }
}
