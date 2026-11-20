package com.dataocean.module.model.entity.vo;

import com.dataocean.common.Times;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 当前配置修订独立于启停状态的连接测试结果。
 */
@Data
public class ModelConnectionTestVO {

    /**
     * 是否返回了预期的合成结构化响应。
     */
    private boolean passed;

    /**
     * 可披露的稳定失败原因，成功时为空。
     */
    private String failureCode;

    /**
     * 平台数据库记录的测试完成时间，按 UTC 存储。
     */
    @JsonIgnore
    private LocalDateTime testedAtUtc;

    /**
     * 向页面返回统一的 UTC 时刻。
     */
    @JsonProperty("testedAt")
    public Instant testedAt() {
        return Times.instant(testedAtUtc);
    }
}
