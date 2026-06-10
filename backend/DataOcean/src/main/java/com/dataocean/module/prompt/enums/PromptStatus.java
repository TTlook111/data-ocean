package com.dataocean.module.prompt.enums;

/**
 * Prompt 模板状态枚举。
 * <p>
 * 状态流转：DRAFT → PENDING_REVIEW → APPROVED / REJECTED
 * </p>
 */
public enum PromptStatus {

    /** 草稿：新建或编辑中的状态 */
    DRAFT("草稿"),

    /** 待审核：已提交审核，等待审核人处理 */
    PENDING_REVIEW("待审核"),

    /** 已通过：审核通过，可以被 Python 端使用 */
    APPROVED("已通过"),

    /** 已拒绝：审核未通过，需要修改后重新提交 */
    REJECTED("已拒绝");

    private final String description;

    PromptStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
