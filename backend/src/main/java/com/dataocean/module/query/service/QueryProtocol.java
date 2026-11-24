package com.dataocean.module.query.service;

import com.dataocean.module.model.service.ModelInvocation;
import com.dataocean.module.query.entity.vo.QueryAnswerRefVO;
import com.dataocean.module.query.entity.vo.QueryEvidenceVO;

import java.util.List;
import java.util.Set;

/**
 * 问数的业务回执、限量结果及经过验证的内部模型协议。
 */
public final class QueryProtocol {

    /**
     * 完成、撤权、取消及失败后均不能继续当前问数轮次。
     */
    static final Set<String> TERMINAL = Set.of(
            "RESULT_DELIVERED",
            "PERMISSION_DENIED",
            "OUT_OF_SCOPE",
            "KNOWLEDGE_INSUFFICIENT",
            "RESOURCE_EXCEEDED",
            "TECHNICAL_FAILURE",
            "USER_CANCELLED",
            "WAIT_EXPIRED");

    /** 协议只通过明确的数据形状使用。 */
    private QueryProtocol() {}

    /** 先前已经核验的提问、补充与结构化口径。 */
    public record MemoryTurn(String requestId, String question, String supplement, Plan plan) {}

    /** 原查询接受时间和可披露的近期对话及更早摘要。 */
    public record Memory(String timeAnchor, List<MemoryTurn> recent, List<MemoryTurn> earlier) {}

    /**
     * 首次派发前固化许可及模型修订，任何凭据都不保存。
     *
     * @param repairRevision 修正节点固定修订，零表示本轮未配置，空值用于旧版已冻结输入
     * @param intentRevision 问题理解节点固定修订，空值仅用于保持升级前旧请求的检索回执
     */
    public record RunInput(
            String signature,
            Memory memory,
            List<Long> bindings,
            long generationRevision,
            long semanticRevision,
            Long outputRevision,
            Long repairRevision,
            Long intentRevision) {}

    /**
     * 检索前的问题理解回执，查询计划继续使用原问题和核验后的正式证据。
     *
     * @param retrievalQuery 归一化的业务检索表述
     */
    public record Intent(String retrievalQuery) {}

    /** 当前已获权的内部证据引用，不包含结果行及 SQL 参数。 */
    public record RatingAnswer(
            QueryAnswerRefVO ref,
            long userId,
            long sourceId,
            String executionId,
            Long bindingId,
            QueryEvidenceVO evidence,
            String usageRefs) {}

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
            QueryEvidenceVO evidence,
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
}
