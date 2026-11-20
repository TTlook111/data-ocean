package com.dataocean.module.model.entity.vo;

import com.dataocean.common.Times;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 调用诊断及当次配置的非敏感快照。
 */
@Data
public class ModelCallVO {

    /**
     * 调用记录号。
     */
    private String id;

    /**
     * 实际业务节点。
     */
    private String nodeCode;

    /**
     * 当次配置名称。
     */
    private String configName;

    /**
     * 当次接口格式。
     */
    private String protocol;

    /**
     * 当次配置修订号。
     */
    private Integer revision;

    /**
     * 服务报告的实际模型。
     */
    private String modelName;

    /**
     * 当次服务地址。
     */
    private String endpoint;

    /**
     * 调用或业务消费状态。
     */
    private String state;

    /**
     * 稳定的失败原因。
     */
    private String failureCode;

    /**
     * 已知耗时，单位毫秒。
     */
    private Integer elapsedMs;

    /**
     * 已知输入用量。
     */
    private Integer inputTokens;

    /**
     * 已知输出用量。
     */
    private Integer outputTokens;

    /**
     * 当次上下文裁剪原因编码列表，可空。
     */
    private String inputLimits;

    /**
     * 数据库中存储的 UTC 记录时间。
     */
    @JsonIgnore
    private LocalDateTime recordedAtUtc;

    /**
     * 明确返回 UTC 时刻，统一由页面按业务时区转换。
     */
    @JsonProperty("recordedAt")
    public Instant recordedAt() {
        return Times.instant(recordedAtUtc);
    }
}
