package com.dataocean.module.datasource.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * 绑定各环境的业务说明附件存储位置和访问凭据。
 */
@Getter
@Setter
@ConfigurationProperties("dataocean.oss")
public class NoteOssProperties {

    /**
     * 私有 Bucket 所在地域的标识。
     */
    private String region;

    /**
     * SDK 使用的 HTTPS 域名；为空时按地域解析。
     */
    private String endpoint;

    /**
     * 对应当前平台库的私有存储空间。
     */
    private String bucket;

    /**
     * 部署环境在 Bucket 内使用的对象键前缀。
     */
    private String objectPrefix = "dataocean";

    /**
     * 指示 endpoint 是已绑定到 Bucket 的自定义域名。
     */
    private boolean useCname;

    /**
     * 仅限该 Bucket 和前缀读写删除的访问凭据标识。
     */
    private String accessKeyId;

    /**
     * SDK 签名使用的私有密钥。
     */
    private String accessKeySecret;
}
