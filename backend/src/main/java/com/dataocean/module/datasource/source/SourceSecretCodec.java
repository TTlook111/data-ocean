package com.dataocean.module.datasource.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 使用部署密钥保护存储中的源库秘密和执行定位证据。来源归属通过认证附加数据绑定。
 */
@Component
@RequiredArgsConstructor
public class SourceSecretCodec {

    /**
     * 平台部署提供的密钥文件及当前写入版本。
     */
    private final SourceConnectionProperties policy;

    /**
     * 生成每次加密独立使用的随机 nonce。
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * AES-GCM 推荐的 96 位 nonce 长度。
     */
    private static final int NONCE_BYTES = 12;

    /**
     * 返回本次写入应记录的密钥版本。
     */
    public String version() {
        return policy.getCredentialKeyVersion();
    }

    /**
     * 将秘密封装为 nonce 和带认证标签的密文。调用者另存密钥版本。
     */
    public byte[] encrypt(String value, String context, String keyVersion) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);

        byte[] clear = value.getBytes(StandardCharsets.UTF_8);
        try {
            // 先为指定来源初始化 GCM，再写入认证后的密文。
            byte[] ciphertext =
                    cipher(Cipher.ENCRYPT_MODE, nonce, context, keyVersion).doFinal(clear);
            return ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException | IOException exception) {
            // 密钥或加密失败直接阻止配置启用，不降级为明文存储。
            throw new IllegalStateException("数据源秘密无法加密", exception);
        } finally {
            // 释放本次加密临时使用的明文字节。
            Arrays.fill(clear, (byte) 0);
        }
    }

    /**
     * 校验封装和来源认证数据后读取运行凭据。
     */
    public String decrypt(byte[] envelope, String context, String keyVersion) {
        if (envelope.length < NONCE_BYTES + 16) {
            // 拒绝不含完整认证标签的封装。
            throw new IllegalStateException("数据源密文封装无效");
        }

        byte[] nonce = Arrays.copyOf(envelope, NONCE_BYTES);
        try {
            // 归属不同或密文被篡改时，GCM认证不能通过。
            byte[] clear = cipher(Cipher.DECRYPT_MODE, nonce, context, keyVersion)
                    .doFinal(envelope, NONCE_BYTES, envelope.length - NONCE_BYTES);
            try {
                // JDBC 只在连接期间持有必要字符串。
                return new String(clear, StandardCharsets.UTF_8);
            } finally {
                // 清除解密过程持有的临时字节。
                Arrays.fill(clear, (byte) 0);
            }
        } catch (GeneralSecurityException | IOException exception) {
            // 不回显失败的密文字节或原始凭据。
            throw new IllegalStateException("数据源秘密无法解密", exception);
        }
    }

    /**
     * 从受控版本文件取得密钥，绑定读写双方约定的资源上下文。
     */
    private Cipher cipher(int mode, byte[] nonce, String context, String keyVersion)
            throws IOException, GeneralSecurityException {
        String keyFile = policy.getCredentialKeyFiles().get(keyVersion);
        if (keyFile == null) {
            // 缺少指定版本时只能等待恢复部署配置。
            throw new IllegalStateException("未配置数据源加密密钥版本");
        }

        byte[] key =
                Base64.getDecoder().decode(Files.readString(Path.of(keyFile)).strip());
        try {
            // 限制为部署约定的 AES-256，防止错误文件被当成有效密钥。
            if (key.length != 32) {
                throw new IllegalStateException("数据源加密密钥长度无效");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher;
        } finally {
            // 密钥初始化完成后清除输入字节数组。
            Arrays.fill(key, (byte) 0);
        }
    }
}
