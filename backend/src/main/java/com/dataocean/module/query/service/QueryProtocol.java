package com.dataocean.module.query.service;

import com.dataocean.module.model.service.ModelInvocation;

import java.time.Instant;
import java.util.List;

/**
 * 问数的业务回执、限量结果及经过验证的内部模型协议。
 */
public final class QueryProtocol {

    /** 协议只通过明确的数据形状使用。 */
    private QueryProtocol() {}

    /** 一轮请求的固定输入与原轮次关系。 */
    public record Ask(
            String sourceId, String question, String previousId, String conversationId, Boolean inheritContext) {}

    /** 接受命令产生的稳定轮次身份。 */
    public record Accepted(String id) {}

    /** 等待时补充原问题缺少的业务条件。 */
    public record Clarification(String answer) {}

    /** 只使用权威来源标识和对当前用户可见的名称。 */
    public record SourceOption(String id, String name) {}

    /** 近期同源轮次只返回基本定位。 */
    public record Turn(String id, String question, String state, Instant createdAt) {}

    /** 最近对话定位到其实际最新轮次。 */
    public record Conversation(String id, String title, String latestRequestId, Instant updatedAt) {}

    /** 先前已经核验的提问、补充与结构化口径。 */
    public record MemoryTurn(String requestId, String question, String supplement, Plan plan) {}

    /** 原查询接受时间和可披露的近期对话及更早摘要。 */
    public record Memory(String timeAnchor, List<MemoryTurn> recent, List<MemoryTurn> earlier) {}

    /** 首次派发前固化的许可依据及模型修订，任何凭据都不保存。 */
    public record RunInput(
            String signature,
            Memory memory,
            List<Long> bindings,
            long generationRevision,
            long semanticRevision,
            Long outputRevision) {}

    /** 展示中使用解释的确切修订，失败不改变查询结果。 */
    public record Explanation(int revision, String status, String text) {}

    /** 发布索引的实际命中定位，结果中不输出向量或内部连接信息。 */
    public record Evidence(String title, int revision, String revisionId, int fragment) {}

    /** 已交付结果的评价身份，与解释和执行代次独立。 */
    public record AnswerRef(String answerId, int answerRevision) {}

    /** 当前已获权的内部证据引用，不包含结果行及 SQL 参数。 */
    public record RatingAnswer(
            AnswerRef ref,
            long userId,
            long sourceId,
            String executionId,
            Long bindingId,
            Evidence evidence,
            String usageRefs) {}

    /** 与实际执行 SQL 对应的结果列，数值仍保留数据库精度。 */
    public record Column(String name, String label) {}

    /** 限量数据表，数据更新时间无可信水位时为空。 */
    public record Data(
            List<Column> columns,
            List<List<String>> rows,
            boolean truncated,
            String unit,
            String scope,
            Instant dataUpdatedAt,
            SeriesWindow series) {}

    /** 真实日序列覆盖的业务日期，缺少记录的日期保持空值。 */
    public record SeriesWindow(String start, String end) {}

    /** 单次回读仍核验当前源和输出权限。 */
    public record Snapshot(
            String id,
            String sourceId,
            String question,
            String supplement,
            String state,
            String executionState,
            boolean cancelRequested,
            String clarification,
            Data result,
            Evidence evidence,
            String sql,
            Instant createdAt,
            Instant executedAt,
            Instant updatedAt,
            String conversationId,
            int runVersion,
            Explanation explanation,
            AnswerRef answerRef) {}

    /** 模型只传受控目录的逻辑标识。 */
    public record Field(String table, String column) {}

    /** 普通业务条件的值由 JDBC 参数绑定。 */
    public record Condition(Field field, String value) {}

    /** 规划节点只给出运算、业务条件和依据归属。 */
    public record Plan(
            String decision,
            String clarification,
            String binding,
            String operation,
            String metric,
            String unit,
            List<Field> fields,
            List<Condition> filters,
            TimeSelection time) {
        /** 允许已有的不带时间维度的计划和验收用例继续使用。 */
        public Plan(
                String decision,
                String clarification,
                String binding,
                String operation,
                String metric,
                String unit,
                List<Field> fields,
                List<Condition> filters) {
            this(decision, clarification, binding, operation, metric, unit, fields, filters, null);
        }
    }

    /** 绑定可信目录的业务日期，默认标识由 Java 计算。 */
    public record TimeSelection(Field field, String mode, String start, String end, Boolean defaulted) {}

    /** 独立校验节点的有限判断。 */
    public record Check(String decision) {}

    /** 持久化的完整执行关联和列血缘用于结果复检。 */
    public record Reasoning(
            Evidence evidence,
            String sql,
            List<Field> projection,
            List<String> modelRevisions,
            Plan plan,
            Long configuration) {}

    /** 强一致召回验证使用冻结摘要。 */
    public record Fragment(int ordinal, String digest) {}

    /** 发布候选和向量模型身份必须与命中代次一致。 */
    public record Index(
            String binding,
            String candidate,
            String revision,
            String fingerprint,
            String collection,
            List<Fragment> fragments,
            ModelInvocation embedding) {}

    /** 索引只回已核验的编号及摘要。 */
    public record Hit(String binding, int ordinal, String digest) {}

    /** 外发模型调用的有界输入。 */
    public record ModelInput(
            ModelInvocation model,
            String question,
            List<QueryCorpus.Snippet> evidence,
            List<String> rules,
            Plan plan,
            Memory memory) {}

    /** 解释只接受本次已交付且授权的有限快照。 */
    public record ExplanationInput(ModelInvocation model, String question, Data result) {}

    /** 模型生成的解释须再经过 Java 的事实核对与输出门禁。 */
    public record ExplanationText(String text) {}
}
