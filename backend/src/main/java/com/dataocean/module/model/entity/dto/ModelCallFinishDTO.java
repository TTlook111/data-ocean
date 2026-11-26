package com.dataocean.module.model.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 只接受提供方结果的可披露诊断字段。
 */
@Data
public class ModelCallFinishDTO {

    /**
     * 提供方确认的实际模型名称，缺失时保留为空。
     */
    @Size(max = 120)
    private String modelName;

    /**
     * 失败的稳定分类，成功响应没有此值。
     */
    @Pattern(regexp = "MODEL_AUTH|MODEL_RATE_LIMIT|MODEL_FORMAT|MODEL_RESULT_UNKNOWN")
    private String failureCode;

    /**
     * 提供方调用耗时，单位毫秒。
     */
    @NotNull
    @PositiveOrZero
    private Integer elapsedMs;

    /**
     * 已报告的输入 Token 数，未知时为空。
     */
    @PositiveOrZero
    private Integer inputTokens;

    /**
     * 已报告的输出 Token 数，未知时为空。
     */
    @PositiveOrZero
    private Integer outputTokens;
}
