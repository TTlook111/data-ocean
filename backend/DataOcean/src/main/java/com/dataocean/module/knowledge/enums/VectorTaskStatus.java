package com.dataocean.module.knowledge.enums;

import lombok.Getter;

/**
 * 向量化任务状态枚举
 */
@Getter
public enum VectorTaskStatus {
    PENDING("待处理"),
    PROCESSING("处理中"),
    COMPLETED("已完成"),
    FAILED("失败");

    /** 状态描述 */
    private final String description;

    VectorTaskStatus(String description) {
        this.description = description;
    }
}
