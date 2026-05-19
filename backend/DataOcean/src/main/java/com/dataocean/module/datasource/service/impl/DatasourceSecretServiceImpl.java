package com.dataocean.module.datasource.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Service
@Slf4j
public class DatasourceSecretServiceImpl implements DatasourceSecretService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] keyBytes;

    public DatasourceSecretServiceImpl(
            @Value("${dataocean.datasource.encrypt-key:${DATAOCEAN_ENCRYPT_KEY:}}")
            String configuredKey) {
        this.keyBytes = normalizeKey(configuredKey);
        log.info("初始化数据源 AES-GCM 加密服务 keyLength={}", this.keyBytes.length);
    }

    @Override
    public String encrypt(String plainPassword) {
        if (!StringUtils.hasText(plainPassword)) {
            throw new BusinessException("数据源密码不能为空");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            log.error("数据源密码加密失败", exception);
            throw new BusinessException(500, "数据源密码加密失败");
        }
    }

    @Override
    public String decrypt(String encryptedPassword) {
        if (!StringUtils.hasText(encryptedPassword)) {
            throw new BusinessException("数据源加密密码不能为空");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedPassword);
            if (combined.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不合法");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.error("数据源密码解密失败", exception);
            throw new BusinessException(500, "数据源密码解密失败");
        }
    }

    private byte[] normalizeKey(String configuredKey) {
        if (!StringUtils.hasText(configuredKey)) {
            throw new BusinessException(500, "未配置 DATAOCEAN_ENCRYPT_KEY");
        }
        byte[] raw = configuredKey.getBytes(StandardCharsets.UTF_8);
        if (raw.length == KEY_BYTES) {
            return raw;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey);
            if (decoded.length == KEY_BYTES) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Not Base64; fall through to a clear configuration error.
        }
        throw new BusinessException(500, "DATAOCEAN_ENCRYPT_KEY 必须是 32 字节明文或 32 字节 Base64 密钥");
    }
}
