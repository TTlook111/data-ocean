package com.dataocean.module.knowledge.enums;

import lombok.Getter;

/**
 * skills.md document lifecycle status.
 */
@Getter
public enum DocStatus {
    DRAFT("草稿"),
    PENDING_REVIEW("待审核"),
    APPROVED("审核通过"),
    INDEXING("索引构建中"),
    PUBLISHED("已发布"),
    DEPRECATED("已废弃");

    private final String description;

    DocStatus(String description) {
        this.description = description;
    }
}
