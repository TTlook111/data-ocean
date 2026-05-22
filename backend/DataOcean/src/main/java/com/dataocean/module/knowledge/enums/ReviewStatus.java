package com.dataocean.module.knowledge.enums;

import lombok.Getter;

/**
 * 审核状态枚举
 */
@Getter
public enum ReviewStatus {
    PENDING("待审核"),
    APPROVED("审核通过"),
    REJECTED("审核拒绝");

    /** 状态描述 */
    private final String description;

    ReviewStatus(String description) {
        this.description = description;
    }
}
