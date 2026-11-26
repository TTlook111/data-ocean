package com.dataocean.module.query.service;

import com.dataocean.module.model.service.ModelInvocation;
import com.dataocean.module.query.entity.dto.QueryEvidenceSnippetDTO;
import com.dataocean.module.query.entity.vo.QueryAnswerRefVO;
import com.dataocean.module.query.entity.vo.QueryEvidenceVO;
import com.fasterxml.jackson.annotation.JsonInclude;

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
     * @param historySummaryRevision 历史摘要固定修订，零表示没有配置，空值保持旧冻结输入
     * @param historySummary 已持久选定的摘要来源及是否采用模型回包
     * @param rerankRevision 候选重排的固定修订，零或空值表示沿用召回顺序
     * @param rerank 首次理解后的候选排序或回退
     * @param clarifiedRerank 同轮补充后生成的独立顺序
     */
    public record RunInput(
            String signature,
            Memory memory,
            List<Long> bindings,
            long generationRevision,
            long semanticRevision,
            Long outputRevision,
            Long repairRevision,
            Long intentRevision,
            Long historySummaryRevision,
            SummaryDecision historySummary,
            Long rerankRevision,
            RerankDecision rerank,
            RerankDecision clarifiedRerank) {}

    /**
     * 摘要模型只引用 Java 提供的已核验轮次，内容由源计划组装。
     *
     * @param requestIds 保留的旧轮次编号
     */
    public record HistorySummary(List<String> requestIds) {}

    /**
     * 选定的上下文先落盘，超时后到达的回包不得改变后续节点输入。
     *
     * @param requestIds 实际保留的旧轮次，全部来自冻结历史
     * @param applied 是否采用了通过核对的模型摘要
     */
    public record SummaryDecision(List<String> requestIds, boolean applied) {}

    /**
     * 检索前的问题理解回执，查询计划继续使用原问题和核验后的正式证据。
     *
     * @param retrievalQuery 归一化的业务检索表述
     * @param conditions 原话中的明确业务条件，旧回执可能缺失
     * @param ambiguous 本轮业务条件不能确定时由调用方澄清
     */
    public record Intent(String retrievalQuery, List<RequestedTerm> conditions, Boolean ambiguous) {
        /**
         * 已冻结的旧回执只包含检索表述。
         */
        public Intent(String retrievalQuery) {
            this(retrievalQuery, null, null);
        }
    }

    /**
     * 用户原话的条件，召回证据前使用业务语言。
     *
     * @param phrase 包含肯定或否定语义的完整原话片段
     * @param value 原话中要求包含或排除的值
     * @param field 原话明确提到的业务维度，可为空
     * @param mode INCLUDE 或 EXCLUDE
     * @param source Java 记录的原问或补充来源
     * @param action 补充对用户条件执行 APPEND、REPLACE 或 REMOVE
     * @param through 补充原文从开头到本次编辑值或字段末尾的原样前缀
     */
    public record RequestedTerm(
            String phrase,
            String value,
            String field,
            String mode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String source,
            @JsonInclude(JsonInclude.Include.NON_NULL) String action,
            @JsonInclude(JsonInclude.Include.NON_NULL) String through) {
        /**
         * 模型及旧回执使用没有来源键的形状。
         */
        public RequestedTerm(String phrase, String value, String field, String mode) {
            this(phrase, value, field, mode, null, null);
        }

        /**
         * 已登记的编辑回执仍可通过完整的原句定位。
         */
        public RequestedTerm(String phrase, String value, String field, String mode, String source, String action) {
            this(phrase, value, field, mode, source, action, null);
        }

        /**
         * Java 按实际发送的原问或补充设置来源。
         */
        RequestedTerm from(String origin) {
            return new RequestedTerm(phrase, value, field, mode, origin, action, through);
        }
    }

    /**
     * 复核可见的已授权发布候选及其排除依据。
     *
     * @param binding 本轮已核验的绑定编号
     * @param ordinal 发布正文片段序号
     * @param reason Java 判定的排除原因
     * @param fixedFilters 发生范围冲突时的已确认固定条件
     * @param evidence 候选的获准证据和执行范围事实
     * @param comparison 使用同一源列规则对固定条件和用户值做的类型化比较
     */
    public record CandidateExclusion(
            String binding,
            int ordinal,
            String reason,
            List<Condition> fixedFilters,
            CandidateEvidence evidence,
            @JsonInclude(JsonInclude.Include.NON_NULL) ConditionComparison comparison) {}

    /**
     * 解释固定规则冲突时，保留对应用户条件和等值事实。
     *
     * @param requested 已核对的用户条件
     * @param equal 固定参数与用户参数在当前源列规则下是否相等
     */
    public record ConditionComparison(Condition requested, boolean equal) {}

    /**
     * 可复核的同名列的物理身份。
     *
     * @param field 模型可见的逻辑表列
     * @param object 对象稳定编号
     * @param generation 对象当前世代
     * @param column 字段稳定编号
     * @param revision 字段修订编号
     */
    public record ColumnIdentity(Field field, String object, long generation, String column, String revision) {}

    /**
     * 独立复核看到参与选路的正式片段和可核对事实。
     *
     * @param snippet 当前允许读取的事实片段和已确认指标
     * @param columns 当前版本实际可用列身份
     * @param observedPlan 已成功编译时的计划，其他候选没有该字段
     * @param actualObjects 已编译语句使用的对象编号
     * @param boundDates 已编译语句实际绑定的日期
     * @param joinKeys 已编译语句真正使用的等值关联键对
     * @param joinAssessment 关联无法编译时，编译器用于判断的已确认关系和日期证据
     */
    public record CandidateEvidence(
            QueryEvidenceSnippetDTO snippet,
            List<ColumnIdentity> columns,
            Plan observedPlan,
            List<String> actualObjects,
            List<String> boundDates,
            List<List<Field>> joinKeys,
            @JsonInclude(JsonInclude.Include.NON_NULL) JoinAssessment joinAssessment) {}

    /**
     * 未通过编译的关联及其判断上下文。
     *
     * @param reason 关联编译的有限排除原因
     * @param requiredTables 当前计划必须连接的逻辑表
     * @param dateField 用于核对适用期的唯一业务日期列
     * @param dateBounds 用于判断适用期的查询起止日期，单日只保留一次
     * @param relations 当前授权发布中编译器实际考察的关联
     */
    public record JoinAssessment(
            String reason,
            List<String> requiredTables,
            Field dateField,
            List<String> dateBounds,
            List<RelationEvidence> relations) {}

    /**
     * 单条已确认关联及本次实际适用性。
     *
     * @param revision 已确认关联的实际修订
     * @param keys 关系两端的全部等值键对
     * @param rule 确认正文中的规则依据
     * @param usage 确认正文中的适用期及其他适用依据
     * @param applicable 该关联是否覆盖当前查询范围
     * @param validFrom 已确认的最早适用日期，包含当天
     * @param validThrough 已确认的最晚适用日期，包含当天
     */
    public record RelationEvidence(
            String revision,
            List<List<Field>> keys,
            String rule,
            String usage,
            boolean applicable,
            @JsonInclude(JsonInclude.Include.NON_NULL) String validFrom,
            @JsonInclude(JsonInclude.Include.NON_NULL) String validThrough) {}

    /**
     * 供独立复核校验用户范围和选路。
     *
     * @param terms 用户原话中的条件片段
     * @param conditions 由确认依据绑定的用户条件
     * @param excluded 当前比较中未采用的候选原因
     * @param selected 当前已选择候选的可核对事实
     */
    public record SelectionReview(
            List<RequestedTerm> terms,
            List<Condition> conditions,
            List<CandidateExclusion> excluded,
            CandidateEvidence selected) {}

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

    /**
     * 业务值由 JDBC 参数绑定，排除操作仅由服务端范围核对产生。
     */
    public record Condition(Field field, String value, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean exclude) {
        /**
         * 沿用等值条件的既有回执形状。
         */
        public Condition(Field field, String value) {
            this(field, value, null);
        }

        /**
         * 缺省时使用等值比较。
         */
        public boolean excluded() {
            return Boolean.TRUE.equals(exclude);
        }
    }

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

    /**
     * 模型仅返回原候选的排列，业务端在应用之前核对完整性。
     *
     * @param order 各候选在召回列表中的零起始位置
     */
    public record RerankOrder(List<Integer> order) {}

    /**
     * 进入后续规划前固定同一候选集合。恢复时用摘要和身份重新核对。
     *
     * @param hits 本阶段采用的完整候选次序
     * @param applied 是否采用了有效重排结果
     */
    public record RerankDecision(List<Hit> hits, boolean applied) {}
}
