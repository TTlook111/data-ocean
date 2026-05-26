package com.dataocean.module.fieldtag.entity.dto;

import lombok.Data;

/**
 * 反馈审核请求 DTO
 * <p>
 * 管理员审核反馈时的请求参数。
 * </p>
 */
@Data
public class FeedbackReviewRequestDTO {

    /** 审核意见 */
    private String reviewComment;
}
