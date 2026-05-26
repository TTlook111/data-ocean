package com.dataocean.module.fieldtag.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户反馈请求 DTO
 * <p>
 * 用户提交对查询结果中字段的反馈（点赞/点踩）。
 * </p>
 */
@Data
public class FeedbackRequestDTO {

    /** 关联的查询任务ID */
    @NotNull(message = "查询任务ID不能为空")
    private Long queryTaskId;

    /** 关联的字段元数据ID */
    @NotNull(message = "字段ID不能为空")
    private Long columnMetaId;

    /** 反馈类型：LIKE/DISLIKE */
    @NotBlank(message = "反馈类型不能为空")
    private String feedbackType;

    /** 原因编码（点踩时必填） */
    private String reasonCode;

    /** 用户补充说明 */
    private String comment;
}
