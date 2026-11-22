package com.dataocean.module.query.service;

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

    /** 只携带有限状态，调用侧不保存外部错误正文。 */
    QueryFailure(String state) {
        this(state, false, null);
    }

    /** 区分公开终态与内部可修复分类。 */
    private QueryFailure(String state, boolean parameterMismatch, String diagnostic) {
        super(state);
        this.state = state;
        this.parameterMismatch = parameterMismatch;
        this.diagnostic = diagnostic;
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

    /** 当前筛选值无法按可信目录类型绑定。 */
    static QueryFailure invalidParameter() {
        return new QueryFailure("KNOWLEDGE_INSUFFICIENT", true, null);
    }

    /** 证据不足不能继续派发 SQL。 */
    static QueryFailure knowledge() {
        return new QueryFailure("KNOWLEDGE_INSUFFICIENT");
    }

    /** 没有任何可用发布绑定，能够确定该次缺口的影响边界。 */
    static QueryFailure noPublishedKnowledge() {
        return new QueryFailure("KNOWLEDGE_INSUFFICIENT", false, "NO_PUBLISHED_KNOWLEDGE");
    }

    /** 连接或内部服务不明确的失败。 */
    static QueryFailure technical() {
        return new QueryFailure("TECHNICAL_FAILURE");
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
