package com.dataocean.module.query.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 查询结果反馈请求 DTO。
 * <p>
 * 替代原始 Map 接收请求体，提供参数校验。
 * </p>
 */
@Data
public class SubmitFeedbackRequest {

    /** 反馈类型：LIKE / DISLIKE */
    @NotBlank(message = "feedbackType 不能为空")
    @Pattern(regexp = "LIKE|DISLIKE", message = "feedbackType 仅支持 LIKE 或 DISLIKE")
    private String feedbackType;

    /** 反馈备注（可选） */
    private String comment;
}
