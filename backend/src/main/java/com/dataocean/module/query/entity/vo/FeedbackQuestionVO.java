package com.dataocean.module.query.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 核查仅引用原问和实际执行依据，不复制查询结果行或绑定参数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackQuestionVO {

    /**
     * 允许核对的原问或已交付回答编号。
     */
    private String answerId;

    /**
     * 当前轮次使用的业务问题。
     */
    private String question;

    /**
     * 原提问人对当前问数轮次已确认的补充。
     */
    private String supplement;

    /**
     * 源端实际执行结束后记录的时刻。
     */
    private Instant executedAt;

    /**
     * 按当前权限可见的正式知识证据。
     */
    private QueryEvidenceVO evidence;

    /**
     * 按当前授权裁剪后的业务数据范围。
     */
    private String scope;

    /**
     * 仅在允许回看实际执行的条件下返回的 SQL。
     */
    private String sql;

    /**
     * 该原问题有不可回看的证据或范围。
     */
    private boolean incomplete;
}
