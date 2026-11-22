package com.dataocean.module.query.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.model.service.ModelRegistry;
import com.dataocean.module.query.mapper.QueryAiStepMapper;
import com.dataocean.module.query.service.QueryProtocol.Check;
import com.dataocean.module.query.service.QueryProtocol.Data;
import com.dataocean.module.query.service.QueryProtocol.ExplanationInput;
import com.dataocean.module.query.service.QueryProtocol.ExplanationText;
import com.dataocean.module.query.service.QueryProtocol.Hit;
import com.dataocean.module.query.service.QueryProtocol.Index;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.ModelInput;
import com.dataocean.module.query.service.QueryProtocol.Plan;

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
        this.factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(32));
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
        if (!allowedSources.contains(source) || worker.getIfAvailable() == null) {
            throw QueryFailure.modelUnavailable();
        }
    }

    /** 未显式批准业务结果外发时停止解释，已交付结果仍保持可用。 */
    void requireExplanationScope(long source) {
        requireScope(source);
        if (!explanationSources.contains(source)) {
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

    /** 通过实际固定向量模型召回并验证代次。 */
    List<Hit> retrieve(Run run, String question, List<Index> indexes, int seconds) {
        var input = Map.of("question", question, "indexes", indexes, "remainingSeconds", seconds);
        return List.of(call(run, "retrieve", "/retrieve", input, Hit[].class));
    }

    /** 生成受控运算与业务条件。 */
    Plan plan(
            Run run,
            long revision,
            int seconds,
            String question,
            List<QueryCorpus.Snippet> evidence,
            List<OutputRuleVO> rules,
            Memory memory) {
        return step(run, "plan", revision, seconds, question, evidence, rules, null, memory, Plan.class);
    }

    /** 提交一次确定因类型不匹配被拒绝的提议，同一动作恢复时仍用原回执。 */
    Plan repairParameter(
            Run run,
            long revision,
            int seconds,
            String question,
            List<QueryCorpus.Snippet> evidence,
            List<OutputRuleVO> rules,
            Plan previous,
            Memory memory) {
        return step(
                run, "repair-parameter", revision, seconds, question, evidence, rules, previous, memory, Plan.class);
    }

    /** 从独立节点取得语义与当前输出条件的明确判断。 */
    String check(
            Run run,
            String kind,
            long revision,
            int seconds,
            String question,
            List<QueryCorpus.Snippet> evidence,
            List<OutputRuleVO> rules,
            Plan plan,
            Memory memory) {
        return step(run, kind, revision, seconds, question, evidence, rules, plan, memory, Check.class)
                .decision();
    }

    /** 解释独立复用原结果，不构造 SQL 或访问业务数据库。 */
    String explain(String id, int attempt, long revision, int seconds, String question, Data result) {
        var step = "explanation-" + attempt;
        boolean lookup = steps.issueExplanation(id, attempt, step) != 1;
        var input = new ExplanationInput(models.invocation(revision, seconds), question, result);
        return call(id, step, lookup, "/explanation", input, ExplanationText.class)
                .text();
    }

    /** 当前部署、版本和活动时间同时约束实际调用。 */
    private <T> T step(
            Run run,
            String kind,
            long revision,
            int seconds,
            String question,
            List<QueryCorpus.Snippet> evidence,
            List<OutputRuleVO> rules,
            Plan plan,
            Memory memory,
            Class<T> type) {
        var input = new ModelInput(
                models.invocation(revision, Math.min(28, seconds)),
                question,
                evidence,
                rules.stream().map(OutputRuleVO::getContent).toList(),
                plan,
                memory);
        return call(run, kind, "/model/" + kind, input, type);
    }

    /** 新派发事实先提交，旧请求的所有后续 HTTP 只能查回执。 */
    private <T> T call(Run run, String phase, String path, Object input, Class<T> type) {
        var step = run.key(phase);
        return call(run.id(), step, steps.issue(run.id(), run.version(), step) != 1, path, input, type);
    }

    /** 机器间 HTTP 断连后仅核对已有回执，避免未知窗口重复外发。 */
    private <T> T call(String id, String step, boolean lookup, String path, Object input, Class<T> type) {
        try {
            return send(id, step, lookup, path, input, type);
        } catch (ResourceAccessException lostReply) {
            // 传输错误不能证明接收结果，恢复入口只读实际已经落盘的回执。
            if (!lookup) {
                try {
                    return send(id, step, true, path, input, type);
                } catch (ResourceAccessException interrupted) {
                    throw new QueryFailure("RECOVERY_PENDING");
                } catch (RestClientException unresolved) {
                    log.info("问数节点回执待核实，request={}，step={}", id, step);
                }
            }
            if (lookup) {
                throw new QueryFailure("RECOVERY_PENDING");
            }
            throw QueryFailure.technical();
        } catch (RestClientResponseException rejected) {
            log.info(
                    "问数内部节点失败，request={}，step={}，http={}",
                    id,
                    step,
                    rejected.getStatusCode().value());
            throw QueryFailure.technical();
        } catch (RestClientException unavailable) {
            log.info(
                    "问数内部节点连接失败，request={}，step={}，分类={}",
                    id,
                    step,
                    unavailable.getClass().getSimpleName());
            throw QueryFailure.technical();
        }
    }

    /** 私有内容、凭据与响应正文不进入日志。 */
    private <T> T send(String id, String step, boolean lookup, String path, Object input, Class<T> type) {
        var result = worker.getObject()
                .mutate()
                .requestFactory(factory)
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
