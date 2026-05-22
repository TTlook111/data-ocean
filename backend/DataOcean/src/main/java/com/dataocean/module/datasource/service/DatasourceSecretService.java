package com.dataocean.module.datasource.service;

/**
 * 数据源密码加解密服务接口
 * <p>
 * 提供数据源连接密码的 AES-256-GCM 加密和解密能力，
 * 确保密码在数据库中以密文形式安全存储。
 * </p>
 *
 * @author dataocean
 */
public interface DatasourceSecretService {

    /**
     * 加密明文密码
     *
     * @param plainPassword 明文密码
     * @return Base64 编码的加密密文（包含 IV + 密文 + Tag）
     */
    String encrypt(String plainPassword);

    /**
     * 解密密文密码
     *
     * @param encryptedPassword Base64 编码的加密密文
     * @return 解密后的明文密码
     */
    String decrypt(String encryptedPassword);
}
