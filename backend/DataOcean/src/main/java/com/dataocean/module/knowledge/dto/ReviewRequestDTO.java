package com.dataocean.module.knowledge.dto;

import lombok.Data;

/**
 * 审核操作请求参数
 */
@Data
public class ReviewRequestDTO {

    /** 审核意见/拒绝原因 */
    private String comment;
}
