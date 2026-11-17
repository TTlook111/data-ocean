package com.dataocean.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 从受控文件读取 HMAC 密钥，并为指纹和核对凭据提供线程安全计算。
 */
@Component
public class SecretKeyProvider {

    /**
     * 只在当前进程内使用的 HMAC 密钥字节，不输出或序列化。
     */
    private final byte[] key;

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public SecretKeyProvider(@Value("${dataocean.secret-key-file}") String file) throws IOException {
        key = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            // 不满足已定密钥强度时终止启动，不降级为内置或无密钥计算。
            throw new IllegalStateException("A private HMAC key of at least 32 bytes is required");
        }
    }

    /**
     * 用受控密钥计算 HMAC，不输出密钥或签名正文。
     */
    public byte[] hmac(String value) {
        try {
            // Mac 是可变对象，每次调用独立创建，避免跨请求并发共享内部状态。
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            // 固定算法或密钥初始化故障无法在当前层恢复，保留原因交由统一边界处理。
            throw new IllegalStateException("Cannot compute command fingerprint", error);
        }
    }

    /**
     * 按当前用途比较规范文本或以恒定时间比较受控摘要。
     */
    public boolean matches(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }
}
