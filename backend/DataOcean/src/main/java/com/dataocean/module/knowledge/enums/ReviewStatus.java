package com.dataocean.module.knowledge.enums;

import lombok.Getter;

/**
 * 审核状态枚举
 */
@Getter
public enum ReviewStatus {
    PENDING("待审核"),
    APPROVED("审核通过"),
    REJECTED("审核拒绝"),
    /**
     * 历史数据未记录。
     * <p>
     * `knowledge_doc_version.review_status` 在 V51 之前从未被写入，历史行的真实审核
     * 状态无法从该表推断。V51 迁移把无法从 `knowledge_review_task` 还原的行标为本值，
     * 以免它们被误读为「待审核」。
     * </p>
     */
    UNKNOWN("历史未记录");

    /** 状态描述 */
    private final String description;

    /**
     * 构造方法
     *
     * @param description 状态描述
     */
    ReviewStatus(String description) {
        this.description = description;
    }
}
