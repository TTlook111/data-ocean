package com.dataocean.module.fieldtag.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 可信度更新请求 DTO
 * <p>
 * 管理员手动设置字段可信度分数的请求参数。
 * </p>
 */
@Data
public class ConfidenceUpdateRequestDTO {

    /** 可信度分数（0-100） */
    @NotNull(message = "可信度分数不能为空")
    @Min(value = 0, message = "可信度分数最小为0")
    @Max(value = 100, message = "可信度分数最大为100")
    private Integer score;

    /** 设置原因 */
    private String reason;
}
