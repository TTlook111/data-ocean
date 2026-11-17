package com.dataocean.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/**
 * 只保存配置和受控文件引用，秘密本体不放入可打印的属性对象。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "dataocean")
public class DataOceanProperties {

    /**
     * 本机 HMAC 密钥文件引用，不保存密钥内容。
     */
    @NotBlank
    private String secretKeyFile;

    /**
     * 没有真实用户活动时的最大空闲秒数。
     */
    @Min(1)
    private long sessionIdleSeconds = 1800;

    /**
     * 一次登录的最长持续秒数。
     */
    @Min(1)
    private long sessionAbsoluteSeconds = 28800;

    /**
     * 影响核对凭据允许使用的秒数。
     */
    @Min(1)
    private long reviewSeconds = 300;

    /**
     * 固定登录窗口允许的最大尝试次数。
     */
    @Min(1)
    private long loginAttemptLimit = 10;

    /**
     * 登录限流的固定窗口秒数。
     */
    @Min(1)
    private long loginWindowSeconds = 300;

    /**
     * 是否启用当前应用的可靠事件派发。
     */
    private boolean outboxEnabled = true;

    /**
     * 可靠事件写入的固定 Redis Stream 名称。
     */
    private String outboxStream = "dataocean:events:domain";

    /**
     * 受控初始化指定的首管理员登录名。
     */
    private String bootstrapUsername;

    /**
     * 受控初始化指定的首管理员姓名。
     */
    private String bootstrapDisplayName;

    /**
     * 受控初始化指定的根部门编号。
     */
    private String bootstrapDepartmentCode;

    /**
     * 受控初始化指定的根部门名称。
     */
    private String bootstrapDepartmentName;

    /**
     * 首管理员初始密码的资源引用，不保存密码本体。
     */
    private String bootstrapPasswordFile;
}
