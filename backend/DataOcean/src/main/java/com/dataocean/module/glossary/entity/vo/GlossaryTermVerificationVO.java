package com.dataocean.module.glossary.entity.vo;

/**
 * 术语当前有效性的只读核验结果。
 *
 * <p>分类来自 {@code GlossaryTermWorkflowServiceImpl} 现有审核与来源核验规则，
 * 目录展示只消费这里的 code/message，不另写较宽松的判断。</p>
 */
public record GlossaryTermVerificationVO(
        boolean valid,
        String code,
        String message,
        String termStatus,
        Long revision,
        Long reviewId,
        String contentHash) {

    public static final String VALID = "VALID";
    public static final String TERM_MISSING = "TERM_MISSING";
    public static final String NOT_APPROVED = "NOT_APPROVED";
    public static final String EVIDENCE_MISSING = "EVIDENCE_MISSING";
    public static final String CONTENT_CHANGED = "CONTENT_CHANGED";
    public static final String SOURCE_INVALID = "SOURCE_INVALID";
    public static final String SYSTEM_ERROR = "SYSTEM_ERROR";
}
