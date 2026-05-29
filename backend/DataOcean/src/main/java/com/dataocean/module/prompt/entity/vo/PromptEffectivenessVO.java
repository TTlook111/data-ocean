package com.dataocean.module.prompt.entity.vo;

import lombok.Data;

/**
 * Prompt 模板效果分析视图对象。
 * <p>
 * 按模板编码+版本号维度统计查询成功率、平均执行时间和用户反馈率。
 * </p>
 */
@Data
public class PromptEffectivenessVO {

    /** 模板编码 */
    private String templateCode;

    /** 版本号 */
    private Integer versionNo;

    /** 总查询次数 */
    private Long totalQueries;

    /** 成功次数 */
    private Long successCount;

    /** 成功率（百分比，如 85.50） */
    private Double successRate;

    /** 平均执行时间（毫秒） */
    private Double avgExecutionTimeMs;

    /** 有反馈的查询数 */
    private Long feedbackCount;

    /** 正面反馈数 */
    private Long positiveFeedbackCount;

    /** 正面反馈率（百分比） */
    private Double positiveFeedbackRate;
}
