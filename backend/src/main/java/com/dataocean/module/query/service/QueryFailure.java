package com.dataocean.module.query.service;

import com.dataocean.module.query.service.QueryProtocol.JoinAssessment;

import java.util.List;

/**
 * 本次轮次的公开终态，底层服务错误不进入结果。
 */
final class QueryFailure extends RuntimeException {

    /** 服务端预先定义的终态编码。 */
    private final String state;
    /** 仅实际参数类型不匹配允许规划节点有限修补。 */
    private final boolean parameterMismatch;
    /** 可供后台归类的固定诊断代码，独立于异常描述。 */
    private final String diagnostic;

    /**
     * 仅供当前获准候选的语义复核使用，不写入用户终态或诊断日志。
     */
    private final JoinAssessment joinAssessment;

    /**
     * Java 已定位的治理缺口，只有确切身份才用于反馈去重。
     */
    private final Gap gap;

    /**
     * 同一确认修订内的规则或字段依赖缺口。
     *
     * @param kind 待补齐的规则或依赖类型
     * @param revision 指标或关联的实际确认修订
     * @param targets 按稳定编号排序的缺失字段依赖
     */
    record Gap(String kind, String revision, List<String> targets) {}

    /** 只携带有限状态，调用侧不保存外部错误正文。 */
    QueryFailure(String state) {
        this(state, false, null);
    }

    /** 区分公开终态与内部可修复分类。 */
    private QueryFailure(String state, boolean parameterMismatch, String diagnostic) {
        this(state, parameterMismatch, diagnostic, null, null);
    }

    /**
     * 内部取证与公开错误状态分开，保留原有候选回退行为。
     */
    private QueryFailure(
            String state, boolean parameterMismatch, String diagnostic, JoinAssessment joinAssessment, Gap gap) {
        super(state);
        this.state = state;
        this.parameterMismatch = parameterMismatch;
        this.diagnostic = diagnostic;
        this.joinAssessment = joinAssessment;
        this.gap = gap;
    }

    /** 持久化的稳定终态。 */
    String state() {
        return state;
    }

    /** 标识 JDBC 派发前的类型判断，模型回执和实际数据原文不写日志。 */
    boolean parameterMismatch() {
        return parameterMismatch;
    }

    /** 诊断只取预定义枚举，可为空表示同类通用原因。 */
    String diagnostic() {
        return diagnostic;
    }

    /**
     * 已授权关系的编译证据供独立复核核对。
     */
    JoinAssessment joinAssessment() {
        return joinAssessment;
    }

    /**
     * 供问数终态生成反馈指纹，未定位的失败不参与自动复用。
     */
    Gap gap() {
        return gap;
    }

    /**
     * 关联不适用或无法确定时继续尝试其他候选，同时保留排除依据。
     */
    static QueryFailure join(JoinAssessment assessment) {
        return join(assessment, null);
    }

    /**
     * 已定位到单一关联缺少适用依据时保留其确切确认修订。
     */
    static QueryFailure join(JoinAssessment assessment, String missingRuleRevision) {
        // 可能适用的关系尚无确认时，不能作为同源候选的确定排除理由。
        var diagnostic = "RELATION_APPLICABILITY_UNVERIFIABLE".equals(assessment.reason()) ? assessment.reason() : null;
        var gap =
                missingRuleRevision == null ? null : new Gap("RELATION_APPLICABILITY", missingRuleRevision, List.of());
        return new QueryFailure("KNOWLEDGE_INSUFFICIENT", false, diagnostic, assessment, gap);
    }

    /** 当前筛选值无法按可信目录类型绑定。 */
    static QueryFailure invalidParameter() {
        return new QueryFailure("KNOWLEDGE_INSUFFICIENT", true, null);
    }

    /** 证据不足不能继续派发 SQL。 */
    static QueryFailure knowledge() {
        return new QueryFailure("KNOWLEDGE_INSUFFICIENT");
    }

    /**
     * 保存已核实的缺口身份，公开状态仍沿用知识不足。
     */
    static QueryFailure knowledge(Gap gap) {
        return new QueryFailure("KNOWLEDGE_INSUFFICIENT", false, null, null, gap);
    }

    /** 没有任何可用发布绑定，能够确定该次缺口的影响边界。 */
    static QueryFailure noPublishedKnowledge() {
        return new QueryFailure("KNOWLEDGE_INSUFFICIENT", false, "NO_PUBLISHED_KNOWLEDGE");
    }

    /** 连接或内部服务不明确的失败。 */
    static QueryFailure technical() {
        return new QueryFailure("TECHNICAL_FAILURE");
    }

    /**
     * 节点结束或无法核实的受控诊断，不保存外部服务正文。
     */
    static QueryFailure technical(String diagnostic) {
        return new QueryFailure("TECHNICAL_FAILURE", false, diagnostic);
    }

    /**
     * 已提供指定表的发布依据，规划仍指向其他表或发布。
     */
    static QueryFailure planTargetMismatch() {
        return new QueryFailure("TECHNICAL_FAILURE", false, "QUERY_PLAN_TARGET_MISMATCH");
    }

    /** 必需模型或调用配置不可用，后台只收到固定诊断。 */
    static QueryFailure modelUnavailable() {
        return new QueryFailure("TECHNICAL_FAILURE", false, "MODEL_CONFIGURATION_UNAVAILABLE");
    }

    /** 执行前已知不具备允许范围。 */
    static QueryFailure denied() {
        return new QueryFailure("PERMISSION_DENIED");
    }

    /** 预算用尽时保留资源失败状态。 */
    static QueryFailure resource() {
        return new QueryFailure("RESOURCE_EXCEEDED");
    }
}
