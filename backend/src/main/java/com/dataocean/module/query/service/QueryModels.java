package com.dataocean.module.query.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.model.service.ModelRegistry;
import com.dataocean.module.query.entity.dto.QueryContextInputDTO;
import com.dataocean.module.query.entity.dto.QueryEvidenceSnippetDTO;
import com.dataocean.module.query.entity.dto.QueryExplanationInputDTO;
import com.dataocean.module.query.entity.dto.QueryModelInputDTO;
import com.dataocean.module.query.entity.dto.QueryRerankInputDTO;
import com.dataocean.module.query.entity.vo.QueryExplanationSelectionVO;
import com.dataocean.module.query.mapper.QueryAiStepMapper;
import com.dataocean.module.query.service.QueryProtocol.Check;
import com.dataocean.module.query.service.QueryProtocol.HistorySummary;
import com.dataocean.module.query.service.QueryProtocol.Hit;
import com.dataocean.module.query.service.QueryProtocol.Index;
import com.dataocean.module.query.service.QueryProtocol.Intent;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.RerankOrder;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

/**
 * 经过部署准入和节点修订的问数外发，仅通过已有内部机器身份。
 */
@Service
@Slf4j
class QueryModels {

    /** 可选历史节点使用更短的机器调用时限，降低可选调用的等待成本。 */
    private static final String HISTORY_PATH = "/history-summary";

    /** 可选重排使用与摘要相同的短机器调用预算。 */
    private static final String RERANK_PATH = "/rerank";

    /** 保持现有受认证地址、短超时和不跟随重定向。 */
    private final ObjectProvider<RestClient> worker;
    /** 模型使用平台节点的当前固定修订。 */
    private final ModelRegistry models;
    /** 已发出的外部动作在正式库留痕，恢复时只查询回执。 */
    private final QueryAiStepMapper steps;
    /** 部署允许发送查询内容的来源。 */
    private final Set<Long> allowedSources;
    /** 部署另外授权发送限量查询结果的来源。 */
    private final Set<Long> explanationSources;
    /** 各次内部调用复用本机 HTTP 连接与固定有界预算。 */
    private final JdkClientHttpRequestFactory factory;

    /** 可选模型不可用时应及时回退。 */
    private final JdkClientHttpRequestFactory optionalFactory;

    /** 复用内部模型客户端与独立结果外发约束。 */
    QueryModels(
            @Qualifier("modelTestPythonClient") ObjectProvider<RestClient> worker,
            ModelRegistry models,
            QueryAiStepMapper steps,
            @Value("${dataocean.query.model-source-ids:}") Set<Long> allowedSources,
            @Value("${dataocean.query.explanation-source-ids:}") Set<Long> explanationSources) {
        this.worker = worker;
        this.models = models;
        this.steps = steps;
        this.allowedSources = allowedSources;
        this.explanationSources = explanationSources;
        var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(32));
        this.optionalFactory = new JdkClientHttpRequestFactory(client);
        optionalFactory.setReadTimeout(Duration.ofSeconds(12));
    }

    /** 同一次查询的节点由真实请求、执行代次及是否完成澄清确定。 */
    record Run(String id, int version, boolean clarified) {
        /** 恢复新代次保持原外发动作，用户补充才形成新阶段内容。 */
        String key(String step) {
            return clarified ? step + "-clarified" : step;
        }
    }

    /** 问数是否可以在当前部署把已获准的内容发送给分配模型。 */
    void requireScope(long source) {
        if (!sourceAdmitted(source) || worker.getIfAvailable() == null) {
            throw QueryFailure.modelUnavailable();
        }
    }

    /**
     * 当前部署是否允许把该来源的问数内容交给模型。
     */
    boolean sourceAdmitted(long source) {
        return allowedSources.contains(source);
    }

    /**
     * 接收新问数前核对节点已分配、启用且符合当前部署的外发准入。
     */
    void requireIntentReady(long source) {
        if (!sourceAdmitted(source)) {
            // 来源未获准时直接反馈该来源的状态，不读取模型绑定。
            throw new BizIllegalException(ErrorCode.QUERY_SOURCE_NOT_ADMITTED, "当前数据源未开通智能问数");
        }
        requireIntentConfiguration();
    }

    /**
     * 来源列表复用提交前的配置核验，同一次列表读取只检查一次。
     */
    boolean intentConfigurationReady() {
        try {
            // 读取当前配置，无需对模型发起请求。
            requireIntentConfiguration();
            return true;
        } catch (BizIllegalException unavailable) {
            // 仍可回看授权历史，提交资格在列表中单独反馈。
            return false;
        }
    }

    /**
     * 核对当前内部客户端及问题理解节点配置。
     */
    private void requireIntentConfiguration() {
        try {
            // 已准入来源仍需启用内部问数服务。
            if (worker.getIfAvailable() == null) {
                throw QueryFailure.modelUnavailable();
            }
            models.verifyActive(models.requireBinding("intent"));
        } catch (QueryFailure | BizIllegalException unavailable) {
            // 配置不完整时在提交处告知，避免产生无法运行的队列项。
            throw new BizIllegalException(ErrorCode.QUERY_INTENT_UNAVAILABLE, "问数服务暂不可用", unavailable);
        }
    }

    /**
     * 查询和结果说明均须在部署允许的数据源范围内。
     */
    boolean explanationSourceAdmitted(long source) {
        return sourceAdmitted(source) && explanationSources.contains(source);
    }

    /** 未显式批准业务结果外发时停止解释，已交付结果仍保持可用。 */
    void requireExplanationScope(long source) {
        requireScope(source);
        if (!explanationSourceAdmitted(source)) {
            throw QueryFailure.denied();
        }
    }

    /** 取稳定节点快照，任务内不换提供方重试。 */
    long revision(String node) {
        try {
            return models.requireBinding(node);
        } catch (BizIllegalException unconfigured) {
            // 权威模型目录缺少当前节点属于配置失败，与业务知识缺口分开。
            throw QueryFailure.modelUnavailable();
        }
    }

    /**
     * 修正节点只在需要重规划时必需，冻结时用零保留未配置事实。
     */
    long repairRevision() {
        Long selected = models.activeBinding("sql-repair");
        return selected == null ? 0L : selected;
    }

    /** 允许无摘要配置的环境继续使用结构化历史。 */
    long historySummaryRevision() {
        Long selected = models.activeBinding(ModelRegistry.HISTORY_SUMMARY);
        return selected == null ? 0L : selected;
    }

    /** 未配置重排时仍可使用已经核验的召回候选。 */
    long rerankRevision() {
        Long selected = models.activeBinding(ModelRegistry.RAG_RERANK);
        return selected == null ? 0L : selected;
    }

    /** 澄清前后共用原问题和旧轮次，摘要只发起一次。 */
    HistorySummary summarize(Run run, long revision, int seconds, String question, Memory memory) {
        return contextStep(
                run,
                ModelRegistry.HISTORY_SUMMARY,
                HISTORY_PATH,
                revision,
                Math.min(9, seconds),
                question,
                memory,
                HistorySummary.class);
    }

    /**
     * 使用问题理解自己的配置整理检索表述，恢复时回读同一阶段的已登记结果。
     */
    Intent understand(Run run, long revision, int seconds, String question, Memory memory) {
        return understood(run, run.key("intent"), revision, seconds, question, memory);
    }

    /**
     * 单独核对澄清原文，使用独立回执避免与拼接式旧请求混用。
     */
    Intent understandSupplement(Run run, long revision, int seconds, String supplement, Memory memory) {
        return understood(run, run.key("supplement-scope"), revision, seconds, supplement, memory);
    }

    /**
     * 原问和补充使用同一配置及范围协议。
     */
    private Intent understood(Run run, String step, long revision, int seconds, String text, Memory memory) {
        var intent = contextStep(run, step, "/intent", revision, Math.min(28, seconds), text, memory, Intent.class);
        var query = intent.retrievalQuery();
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 600) {
            // 旧版或异常响应不能作为下一步检索输入。
            throw QueryFailure.technical();
        }
        return new Intent(query.strip(), intent.conditions(), intent.ambiguous());
    }

    /** 上下文节点恢复只读取冻结修订及原输入的回执，无需传递模型凭据。 */
    private <T> T contextStep(
            Run run,
            String step,
            String path,
            long revision,
            int seconds,
            String question,
            Memory memory,
            Class<T> type) {
        boolean lookup = steps.issue(run.id(), run.version(), step) != 1;
        var reference = new QueryContextInputDTO<>(models.revisionReference(revision), question, memory);
        if (lookup) {
            // 只有已登记的实际结果可恢复，不能重新外发。
            return call(run.id(), step, true, path, reference, type);
        }
        var input = new QueryContextInputDTO<>(models.invocation(revision, seconds), question, memory);
        return call(run.id(), step, false, path, input, reference, type);
    }

    /** 通过实际固定向量模型召回并验证代次。 */
    List<Hit> retrieve(Run run, String question, List<Index> indexes, int seconds) {
        var input = Map.of("question", question, "indexes", indexes, "remainingSeconds", seconds);
        return List.of(call(run, "retrieve", "/retrieve", input, Hit[].class));
    }

    /** 每个召回阶段只发一次真实排序请求，凭据不进入回执查询。 */
    RerankOrder rerank(Run run, long revision, int seconds, String query, List<String> texts) {
        var step = run.key(ModelRegistry.RAG_RERANK);
        boolean lookup = steps.issue(run.id(), run.version(), step) != 1;
        var receipt = new QueryRerankInputDTO<>(models.revisionReference(revision), query, texts);
        if (lookup) {
            // 已登记过的阶段只读原检查点，即使当前模型配置被停用也无需凭据。
            return call(run.id(), step, true, RERANK_PATH, receipt, RerankOrder.class);
        }
        var input = new QueryRerankInputDTO<>(models.invocation(revision, Math.min(9, seconds)), query, texts);
        return call(run.id(), step, false, RERANK_PATH, input, receipt, RerankOrder.class);
    }

    /** 生成受控运算与业务条件。 */
    Plan plan(
            Run run,
            long revision,
            int seconds,
            String question,
            List<QueryEvidenceSnippetDTO> evidence,
            List<OutputRuleVO> rules,
            Memory memory) {
        return step(run, "plan", revision, seconds, question, evidence, rules, null, memory, null, null, Plan.class);
    }

    /**
     * 新轮次向规划节点提供与发布证据分开的用户条件。
     */
    Plan plan(
            Run run,
            long revision,
            int seconds,
            String question,
            List<QueryEvidenceSnippetDTO> evidence,
            List<OutputRuleVO> rules,
            Memory memory,
            List<QueryProtocol.RequestedTerm> terms) {
        return step(run, "plan", revision, seconds, question, evidence, rules, null, memory, terms, null, Plan.class);
    }

    /** 提交一次确定因类型不匹配被拒绝的提议，同一动作恢复时仍用原回执。 */
    Plan repairParameter(
            Run run,
            long revision,
            int seconds,
            String question,
            List<QueryEvidenceSnippetDTO> evidence,
            List<OutputRuleVO> rules,
            Plan previous,
            Memory memory) {
        return step(
                run,
                "repair-parameter",
                revision,
                seconds,
                question,
                evidence,
                rules,
                previous,
                memory,
                null,
                null,
                Plan.class);
    }

    /** 从独立节点取得语义与当前输出条件的明确判断。 */
    String check(
            Run run,
            String kind,
            long revision,
            int seconds,
            String question,
            List<QueryEvidenceSnippetDTO> evidence,
            List<OutputRuleVO> rules,
            Plan plan,
            Memory memory) {
        return step(run, kind, revision, seconds, question, evidence, rules, plan, memory, null, null, Check.class)
                .decision();
    }

    /**
     * 独立复核实际查询范围与未采用候选的排除原因。
     */
    String checkSelection(
            Run run,
            long revision,
            int seconds,
            String question,
            List<QueryEvidenceSnippetDTO> evidence,
            List<OutputRuleVO> rules,
            Plan plan,
            Memory memory,
            QueryProtocol.SelectionReview selection) {
        return step(
                        run,
                        "semantic",
                        revision,
                        seconds,
                        question,
                        evidence,
                        rules,
                        plan,
                        memory,
                        null,
                        selection,
                        Check.class)
                .decision();
    }

    /**
     * 模型只选择已获准的结果事实，保留现有调用回执与说明修订。
     */
    List<Integer> explain(String id, int attempt, long revision, int seconds, String scope, List<String> facts) {
        var step = "explanation-" + attempt;
        boolean lookup = steps.issueExplanation(id, attempt, step) != 1;
        var input = new QueryExplanationInputDTO(models.invocation(revision, seconds), scope, facts);
        return call(id, step, lookup, "/explanation", input, QueryExplanationSelectionVO.class)
                .getFactIds();
    }

    /** 当前部署、固定版本和单次时限约束实际调用。 */
    private <T> T step(
            Run run,
            String kind,
            long revision,
            int seconds,
            String question,
            List<QueryEvidenceSnippetDTO> evidence,
            List<OutputRuleVO> rules,
            Plan plan,
            Memory memory,
            List<QueryProtocol.RequestedTerm> terms,
            QueryProtocol.SelectionReview selection,
            Class<T> type) {
        var input = new QueryModelInputDTO(
                models.invocation(revision, Math.min(28, seconds)),
                question,
                evidence,
                rules.stream().map(OutputRuleVO::getContent).toList(),
                plan,
                memory,
                terms,
                selection);
        return call(run, kind, "/model/" + kind, input, type);
    }

    /** 新派发事实先提交，旧请求的所有后续 HTTP 只能查回执。 */
    private <T> T call(Run run, String phase, String path, Object input, Class<T> type) {
        var step = run.key(phase);
        return call(run.id(), step, steps.issue(run.id(), run.version(), step) != 1, path, input, type);
    }

    /** 机器间 HTTP 断连后仅核对已有回执，避免未知窗口重复外发。 */
    private <T> T call(String id, String step, boolean lookup, String path, Object input, Class<T> type) {
        return call(id, step, lookup, path, input, input, type);
    }

    /**
     * 首次派发断连时使用指定的回读输入，同一阶段不会再次外发模型请求。
     */
    private <T> T call(
            String id, String step, boolean lookup, String path, Object input, Object receipt, Class<T> type) {
        try {
            return send(id, step, lookup, path, input, type);
        } catch (ResourceAccessException lostReply) {
            // 原调用传输中断，后续请求只能读取同一回执。
            if (lookup) {
                // 回执服务也失联时交给下一代次继续核实。
                throw pendingReceipt(id, step, path);
            }
            return call(id, step, true, path, receipt, type);
        } catch (RestClientResponseException rejected) {
            int status = rejected.getStatusCode().value();
            if (!lookup && (status >= 500 || status == 425 || status == 409)) {
                // 一次请求报错也可能已经保存回执，先核实原节点。
                return call(id, step, true, path, receipt, type);
            }
            var body = rejected.getResponseBodyAsString();
            if (status == 425 && body.contains("QUERY_STEP_RUNNING")) {
                // 执行器确认原节点还在运行，保留当前轮次。
                throw new QueryFailure("RECOVERY_PENDING");
            }
            if (status >= 500
                    || body.contains("QUERY_CHECKPOINT_UNAVAILABLE")
                    || body.contains("QUERY_STEP_OUTCOME_UNKNOWN")) {
                // 没有可靠回执时按原登记时间有界核实。
                throw pendingReceipt(id, step, path);
            }
            log.info("问数内部节点失败，request={}，step={}，http={}", id, step, status);
            throw QueryFailure.technical(body.contains("\"TIMEOUT\"") ? "QUERY_STEP_TIMEOUT" : "QUERY_STEP_FAILED");
        } catch (RestClientException unavailable) {
            // 无法解码的回包不能用于下一步骤。
            log.info("问数内部节点回包无效，request={}，step={}", id, step);
            throw QueryFailure.technical("QUERY_STEP_FAILED");
        }
    }

    /**
     * 查询节点短暂失联后由同轮次回查，说明沿用自己的修订规则。
     */
    private QueryFailure pendingReceipt(String id, String step, String path) {
        if ("/explanation".equals(path) || steps.canVerify(id, step)) {
            // 等待期间始终保留节点登记，之后仍只发送回读命令。
            return new QueryFailure("RECOVERY_PENDING");
        }
        return QueryFailure.technical("QUERY_STEP_UNCONFIRMED");
    }

    /** 私有内容、凭据与响应正文不进入日志。 */
    private <T> T send(String id, String step, boolean lookup, String path, Object input, Class<T> type) {
        var result = worker.getObject()
                .mutate()
                .requestFactory(HISTORY_PATH.equals(path) || RERANK_PATH.equals(path) ? optionalFactory : factory)
                .build()
                .post()
                .uri("/internal/v1/query" + path)
                .header("X-Query-Id", id)
                .header("X-Query-Step", step)
                .header("X-Query-Lookup", Boolean.toString(lookup))
                .body(input)
                .retrieve()
                .body(type);
        if (result == null) {
            throw QueryFailure.technical();
        }
        return result;
    }
}
