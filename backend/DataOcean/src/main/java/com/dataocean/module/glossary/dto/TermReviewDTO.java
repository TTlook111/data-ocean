package com.dataocean.module.glossary.dto;

import lombok.Data;

/**
 * 术语审核请求 DTO。
 */
@Data
public class TermReviewDTO {
    private Long revision;

    /** 是否通过 */
    private boolean approved;
    /** 审核原因 */
    private String reason = "";
}
