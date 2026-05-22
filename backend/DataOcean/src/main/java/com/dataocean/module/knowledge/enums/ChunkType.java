package com.dataocean.module.knowledge.enums;

import lombok.Getter;

/**
 * 知识切片类型枚举
 */
@Getter
public enum ChunkType {
    TABLE_DESC("表说明"),
    JOIN_PATH("关联路径"),
    METRIC("指标口径"),
    FIELD_NOTE("字段防坑");

    /** 类型描述 */
    private final String description;

    ChunkType(String description) {
        this.description = description;
    }
}
