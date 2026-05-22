package com.dataocean.module.knowledge.enums;

import lombok.Getter;

/**
 * 知识文档状态枚举
 */
@Getter
public enum DocStatus {
    DRAFT("草稿"),
    PENDING_REVIEW("待审核"),
    APPROVED("审核通过"),
    PUBLISHED("已发布"),
    DEPRECATED("已废弃");

    /** 状态描述 */
    private final String description;

    DocStatus(String description) {
        this.description = description;
    }
}
