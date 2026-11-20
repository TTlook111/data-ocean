package com.dataocean.module.model.entity.vo;

import com.dataocean.common.Times;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 调用诊断及当次配置的非敏感快照。
 *
 * @param id 调用记录号
 * @param nodeCode 实际业务节点
 * @param configName 当次配置名称
 * @param protocol 当次接口格式
 * @param revision 当次配置修订号
 * @param modelName 服务报告的实际模型
 * @param endpoint 当次服务地址
 * @param state 调用或业务消费状态
 * @param failureCode 稳定的失败原因
 * @param elapsedMs 已知耗时，单位毫秒
 * @param inputTokens 已知输入用量
 * @param outputTokens 已知输出用量
 * @param inputLimits 当次上下文裁剪原因编码列表，可空
 * @param recordedAtUtc 数据库中存储的 UTC 记录时间
 */
public record ModelCallVO(
        String id, String nodeCode, String configName, String protocol, Integer revision,
        String modelName, String endpoint, String state, String failureCode, Integer elapsedMs,
        Integer inputTokens, Integer outputTokens, String inputLimits, @JsonIgnore LocalDateTime recordedAtUtc) {

    /**
     * 明确返回 UTC 时刻，统一由页面按业务时区转换。
     */
    @JsonProperty("recordedAt")
    public Instant recordedAt() {
        return Times.instant(recordedAtUtc);
    }
}
