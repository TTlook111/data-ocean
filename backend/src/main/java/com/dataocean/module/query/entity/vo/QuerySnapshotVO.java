package com.dataocean.module.query.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次回读仍核验当前源和输出权限。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuerySnapshotVO {

    /**
     * 可稳定定位当前请求或记录的编号。
     */
    private String id;

    /**
     * 本轮问数使用的数据源编号。
     */
    private String sourceId;

    /**
     * 当前轮次使用的业务问题。
     */
    private String question;

    /**
     * 原提问人对当前问数轮次已确认的补充。
     */
    private String supplement;

    /**
     * 表示当前阶段的稳定状态编码。
     */
    private String state;

    /**
     * 源端只读执行所处的独立状态。
     */
    private String executionState;

    /**
     * 技术故障时可向提问人披露的阶段及原因。
     */
    private String failureDetail;

    /**
     * 是否已经记录用户的取消意图。
     */
    private boolean cancelRequested;

    /**
     * 当前轮需要用户确认的业务问题。
     */
    private String clarification;

    /**
     * 经当前授权复核的有限结果。
     */
    private QueryDataVO result;

    /**
     * 按当前权限可见的正式知识证据。
     */
    private QueryEvidenceVO evidence;

    /**
     * 仅在允许回看实际执行的条件下返回的 SQL。
     */
    private String sql;

    /**
     * 实际接收或创建记录的 UTC 时刻。
     */
    private Instant createdAt;

    /**
     * 源端实际执行结束后记录的时刻。
     */
    private Instant executedAt;

    /**
     * 权威状态最后变化的 UTC 时刻。
     */
    private Instant updatedAt;

    /**
     * 本轮归属的同一对话。
     */
    private String conversationId;

    /**
     * 请求执行轮次的当前版本。
     */
    private int runVersion;

    /**
     * 会话当前可展示的解释及其修订。
     */
    private QueryExplanationVO explanation;

    /**
     * 已交付结果用于评价的稳定引用。
     */
    private QueryAnswerRefVO answerRef;
}
