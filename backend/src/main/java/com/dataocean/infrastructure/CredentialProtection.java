package com.dataocean.infrastructure;

/**
 * 平台统一的版本化凭据密文边界，调用方提供各自的资源归属。
 */
public interface CredentialProtection {

    /**
     * 取得当前写入使用的部署密钥版本。
     */
    String version();

    /**
     * 用部署密钥加密凭据，并认证资源归属。
     */
    byte[] encrypt(String value, String context, String keyVersion);

    /**
     * 核验版本和资源归属，临时解密实际调用所需的凭据。
     */
    String decrypt(byte[] envelope, String context, String keyVersion);
}
