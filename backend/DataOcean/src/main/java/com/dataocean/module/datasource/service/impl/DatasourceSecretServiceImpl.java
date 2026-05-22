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

/**
 * 数据源密码加解密服务实现类
 * <p>
 * 使用 AES-256-GCM 算法对数据源连接密码进行加密和解密。
 * 加密时随机生成 12 字节 IV，密文格式为 Base64(IV + 密文 + AuthTag)。
 * 密钥通过配置项或环境变量注入，支持 32 字节明文或 Base64 编码格式。
 * </p>
 *
 * @author dataocean
 */
@Service
@Slf4j
public class DatasourceSecretServiceImpl implements DatasourceSecretService {

    /** 加密算法名称 */
    private static final String ALGORITHM = "AES";
    /** 加密转换模式：AES/GCM/NoPadding */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** AES 密钥长度（字节） */
    private static final int KEY_BYTES = 32;
    /** GCM 初始化向量长度（字节） */
    private static final int IV_BYTES = 12;
    /** GCM 认证标签长度（位） */
    private static final int TAG_BITS = 128;
    /** 安全随机数生成器 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 规范化后的 AES 密钥字节数组 */
    private final byte[] keyBytes;

    /**
     * 构造方法，初始化加密密钥
     *
     * @param configuredKey 配置的加密密钥（支持 32 字节明文或 Base64 编码）
     */
    public DatasourceSecretServiceImpl(
            @Value("${dataocean.datasource.encrypt-key:${DATAOCEAN_ENCRYPT_KEY:}}")
            String configuredKey) {
        this.keyBytes = normalizeKey(configuredKey);
        log.info("初始化数据源 AES-GCM 加密服务 keyLength={}", this.keyBytes.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String encrypt(String plainPassword) {
        if (!StringUtils.hasText(plainPassword)) {
            throw new BusinessException("数据源密码不能为空");
        }
        try {
            // 生成随机 IV
            byte[] iv = new byte[IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            // 初始化 AES-GCM 加密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            // 执行加密
            byte[] encrypted = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            // 拼接 IV + 密文，便于解密时提取 IV
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            // 返回 Base64 编码结果
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            log.error("数据源密码加密失败", exception);
            throw new BusinessException(500, "数据源密码加密失败");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String decrypt(String encryptedPassword) {
        if (!StringUtils.hasText(encryptedPassword)) {
            throw new BusinessException("数据源加密密码不能为空");
        }
        try {
            // Base64 解码获取 IV + 密文
            byte[] combined = Base64.getDecoder().decode(encryptedPassword);
            if (combined.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不合法");
            }
            // 分离 IV 和密文
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            // 初始化 AES-GCM 解密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            // 执行解密并返回明文
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.error("数据源密码解密失败", exception);
            throw new BusinessException(500, "数据源密码解密失败");
        }
    }

    /**
     * 规范化加密密钥
     * <p>
     * 支持两种格式：32 字节 UTF-8 明文，或 Base64 编码的 32 字节密钥。
     * 格式不符时抛出异常阻止服务启动。
     * </p>
     *
     * @param configuredKey 配置的密钥字符串
     * @return 32 字节的密钥字节数组
     */
    private byte[] normalizeKey(String configuredKey) {
        if (!StringUtils.hasText(configuredKey)) {
            throw new BusinessException(500, "未配置 DATAOCEAN_ENCRYPT_KEY");
        }
        // 尝试直接作为 UTF-8 字节使用
        byte[] raw = configuredKey.getBytes(StandardCharsets.UTF_8);
        if (raw.length == KEY_BYTES) {
            return raw;
        }
        // 尝试 Base64 解码
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey);
            if (decoded.length == KEY_BYTES) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 非 Base64 格式，继续抛出配置错误
        }
        throw new BusinessException(500, "DATAOCEAN_ENCRYPT_KEY 必须是 32 字节明文或 32 字节 Base64 密钥");
    }
}
