package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.model.service.ModelInvocation;
import com.dataocean.module.model.service.ModelRegistry;
import com.dataocean.module.model.service.ModelRegistry.RevisionReference;
import com.dataocean.module.query.mapper.QueryAiStepMapper;
import com.dataocean.module.query.service.QueryModels.Run;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.Plan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通过真实 RestClient 协议核对数据源准入、节点身份、恢复手势和错误分类。
 */
class QueryModelsTest {

    /** 当前节点分配与外发登记由测试明确给出。 */
    private final ModelRegistry registry = mock(ModelRegistry.class);

    private final QueryAiStepMapper steps = mock(QueryAiStepMapper.class);
    private final Run run = new Run("query-a", 3, false);
    private final Memory memory = new Memory("2026-11-22T00:00:00Z", List.of(), List.of());

    /**
     * 每个上传阶段交换自己的编译后协议，且没有部署准入的业务数据不外发。
     */
    @Test
    void dispatchesDifferentContractsAndHonorsScope() {
        var seen = new ArrayList<String>();
        var models = client((request, bytes, execution) -> {
            var path = request.getURI().getPath();
            seen.add(request.getHeaders().getFirst("X-Query-Step") + ":"
                    + request.getHeaders().getFirst("X-Query-Lookup"));
            if (path.endsWith("/retrieve")) {
                return response(HttpStatus.OK, "[{\"binding\":\"6\",\"ordinal\":0,\"digest\":\"abc\"}]");
            }
            if (path.endsWith("/plan") || path.endsWith("/repair-parameter")) {
                return response(HttpStatus.OK, "{\"decision\":\"READY\",\"filters\":[],\"fields\":[]}");
            }
            if (path.endsWith("/explanation")) {
                // 说明节点只接收可披露范围和可信事实，返回引用。
                assertThat(new String(bytes, StandardCharsets.UTF_8))
                        .contains("\"scope\":\"末轮范围\"", "\"facts\":[\"妥投量为 4 件。\"]")
                        .doesNotContain("\"question\"", "\"result\"");
                return response(HttpStatus.OK, "{\"factIds\":[1]}");
            }
            return response(HttpStatus.OK, "{\"decision\":\"ALLOW\"}");
        });
        when(registry.requireBinding("sql-generate")).thenReturn(9L);
        when(steps.issue("query-a", 3, "plan")).thenReturn(1);
        when(steps.issueExplanation("query-a", 2, "explanation-2")).thenReturn(1);
        models.requireScope(10);
        models.requireExplanationScope(10);
        assertThatThrownBy(() -> models.requireScope(12))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("TECHNICAL_FAILURE");
        assertThatThrownBy(() -> models.requireExplanationScope(11))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("PERMISSION_DENIED");
        assertThat(models.revision("sql-generate")).isEqualTo(9);
        assertThat(models.retrieve(run, "净能耗", List.of(), 10).get(0).binding()).isEqualTo("6");
        Plan plan = models.plan(run, 9, 35, "净能耗", List.of(), List.of(), memory);
        assertThat(plan.decision()).isEqualTo("READY");
        assertThat(models.repairParameter(new Run("query-a", 3, true), 9, 10, "净能耗", List.of(), List.of(), plan, memory)
                        .decision())
                .isEqualTo("READY");
        assertThat(models.check(run, "semantic", 11, 12, "净能耗", List.of(), List.of(), plan, memory))
                .isEqualTo("ALLOW");
        assertThat(models.explain("query-a", 2, 11, 8, "末轮范围", List.of("妥投量为 4 件。")))
                .containsExactly(1);
        assertThat(seen)
                .containsExactly(
                        "retrieve:true",
                        "plan:false",
                        "repair-parameter-clarified:true",
                        "semantic:true",
                        "explanation-2:false");
    }

    /**
     * 问题理解以独立的阶段和模型修订派发，恢复时读取原回执。
     */
    @Test
    void dispatchesAndRecoversIntent() {
        when(registry.invocation(31L, 28))
                .thenReturn(new ModelInvocation(
                        "31", "OPENAI_CHAT_COMPLETIONS", "https://provider.invalid", "sample", 28, "fixture"))
                .thenThrow(BizIllegalException.state("当前模型已停用"));
        when(registry.revisionReference(31L))
                .thenReturn(
                        new RevisionReference("31", "OPENAI_CHAT_COMPLETIONS", "https://provider.invalid", "sample"));
        when(steps.issue("query-a", 3, "intent")).thenReturn(1, 0);
        var seen = new ArrayList<String>();
        var models = client((request, bytes, execution) -> {
            assertThat(request.getURI().getPath()).endsWith("/intent");
            assertThat(request.getHeaders().getFirst("X-Query-Step")).isEqualTo("intent");
            var body = new String(bytes, StandardCharsets.UTF_8);
            assertThat(body).contains("\"revisionId\":\"31\"");
            var lookup = request.getHeaders().getFirst("X-Query-Lookup");
            seen.add(lookup);
            if ("false".equals(lookup)) {
                // 首次断连后，实际发送给回读接口的身份不含原凭据。
                assertThat(body).contains("\"apiKey\"");
                throw new ResourceAccessException("lost intent reply");
            }
            assertThat(body).doesNotContain("\"apiKey\"", "\"timeoutSeconds\"");
            return response(HttpStatus.OK, "{\"retrievalQuery\":\"按线路查询物流状态\"}");
        });

        assertThat(models.understand(run, 31, 60, "物流状态", memory).retrievalQuery())
                .isEqualTo("按线路查询物流状态");
        assertThat(models.understand(run, 31, 60, "物流状态", memory).retrievalQuery())
                .isEqualTo("按线路查询物流状态");
        assertThat(seen).containsExactly("false", "true", "true");
        verify(registry).invocation(31L, 28);
    }

    /**
     * 继续查回原排序和澄清回执时，不能重复发送凭据或混用问题理解的阶段。
     */
    @Test
    void distinguishesRerankAndSupplementReceipts() {
        when(registry.invocation(31L, 9))
                .thenReturn(
                        new ModelInvocation("31", "COHERE_RERANK", "https://provider.invalid", "sample", 9, "fixture"));
        when(registry.revisionReference(31L))
                .thenReturn(new RevisionReference("31", "COHERE_RERANK", "https://provider.invalid", "sample"));
        when(steps.issue("query-a", 3, "rag-rerank")).thenReturn(1, 0);
        var seen = new ArrayList<String>();
        var models = client((request, bytes, execution) -> {
            // 验证真实内部请求使用独立的步骤标识和正确的回读标志。
            var step = request.getHeaders().getFirst("X-Query-Step");
            var lookup = request.getHeaders().getFirst("X-Query-Lookup");
            seen.add(step + ":" + lookup);
            var body = new String(bytes, StandardCharsets.UTF_8);
            if ("true".equals(lookup)) {
                // 回执查询只带固定身份和原确认输入。
                assertThat(body).doesNotContain("apiKey");
            }
            if ("rag-rerank".equals(step)) {
                // 重排只能返回原候选已登记的顺序。
                return response(HttpStatus.OK, "{\"order\":[1,0]}");
            }
            return response(HttpStatus.OK, "{\"retrievalQuery\":\"   \"}");
        });

        assertThat(models.rerank(run, 31, 26, "路线", List.of("甲", "乙")).order()).containsExactly(1, 0);
        assertThat(models.rerank(run, 31, 6, "路线", List.of("甲", "乙")).order()).containsExactly(1, 0);
        assertThatThrownBy(() -> models.understandSupplement(run, 31, 9, "华东区", memory))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("TECHNICAL_FAILURE");
        assertThat(seen).containsExactly("rag-rerank:false", "rag-rerank:true", "supplement-scope:true");
    }

    /**
     * 首次提问检验模型准入，缺少可选节点时使用已设计的回退决定。
     */
    @Test
    void checksRequiredIntentAndOptionalNodesBeforeDispatch() {
        var models = client((request, bytes, execution) -> {
            // 准入或读取分配时只访问模型配置，不能外发业务问题。
            throw new AssertionError("前置检查不得发起模型调用");
        });
        when(registry.requireBinding("intent")).thenReturn(31L);
        models.requireIntentReady(10L);
        verify(registry).verifyActive(31L);
        assertThatThrownBy(() -> models.requireIntentReady(12L))
                .isInstanceOfSatisfying(BizIllegalException.class, error -> {
                    // 来源未准入和模型未配置使用不同的业务码。
                    assertThat(error.getCode()).isEqualTo(ErrorCode.QUERY_SOURCE_NOT_ADMITTED.getCode());
                });
        verify(registry).requireBinding("intent");
        when(registry.requireBinding("intent")).thenThrow(BizIllegalException.state("未配置"));
        assertThatThrownBy(() -> models.requireIntentReady(10L))
                .isInstanceOfSatisfying(BizIllegalException.class, error -> {
                    // 已准入来源才提示检查问题理解配置。
                    assertThat(error.getCode()).isEqualTo(ErrorCode.QUERY_INTENT_UNAVAILABLE.getCode());
                });
        when(registry.requireBinding("sql-generate")).thenThrow(BizIllegalException.state("未配置"));
        assertThatThrownBy(() -> models.revision("sql-generate")).isInstanceOf(QueryFailure.class);

        assertThat(models.repairRevision()).isZero();
        assertThat(models.historySummaryRevision()).isZero();
        assertThat(models.rerankRevision()).isZero();
    }

    /**
     * 真正断连只查回执，旧代次失败保留恢复待核实。
     */
    @Test
    void transportErrorUsesLookupWithoutRedispatch() {
        when(steps.issue("query-a", 3, "retrieve")).thenReturn(1);
        var calls = new ArrayList<String>();
        var models = client((request, bytes, execution) -> {
            String lookup = request.getHeaders().getFirst("X-Query-Lookup");
            calls.add(lookup);
            if ("false".equals(lookup)) {
                throw new ResourceAccessException("lost reply");
            }
            return response(HttpStatus.OK, "[]");
        });
        assertThat(models.retrieve(run, "妥投量", List.of(), 9)).isEmpty();
        assertThat(calls).containsExactly("false", "true");

        when(steps.canVerify("query-a", "retrieve")).thenReturn(true);
        var interrupted = client((request, bytes, execution) -> {
            throw new ResourceAccessException("offline");
        });
        assertThatThrownBy(() -> interrupted.retrieve(run, "妥投量", List.of(), 9))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("RECOVERY_PENDING");
        when(steps.issue("query-a", 3, "retrieve")).thenReturn(0);
        assertThatThrownBy(() -> interrupted.retrieve(run, "妥投量", List.of(), 9))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("RECOVERY_PENDING");
    }

    /**
     * 传输超时后原节点仍在运行，接管只读原回执并继续。
     */
    @Test
    void resumesWithLateReceiptWithoutResendingModelWork() {
        when(steps.issue("query-a", 3, "retrieve")).thenReturn(1);
        when(steps.canVerify("query-a", "retrieve")).thenReturn(true);
        var calls = new ArrayList<String>();
        var models = client((request, bytes, execution) -> {
            // 同一个节点的首次回查发现仍在运行，接管后取得迟到回执。
            calls.add(request.getHeaders().getFirst("X-Query-Lookup"));
            return switch (calls.size()) {
                case 1 -> throw new ResourceAccessException("response timeout");
                case 2 -> response(HttpStatus.TOO_EARLY, "{\"detail\":\"QUERY_STEP_RUNNING\"}");
                default -> response(HttpStatus.OK, "[]");
            };
        });

        assertThatThrownBy(() -> models.retrieve(run, "物流状态", List.of(), 19))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("RECOVERY_PENDING");
        assertThat(models.retrieve(new Run("query-a", 4, false), "物流状态", List.of(), 19))
                .isEmpty();
        assertThat(calls).containsExactly("false", "true", "true");
    }

    /** 摘要节点的澄清回读保持同一阶段，并只携带固定修订标识。 */
    @Test
    void summaryUsesItsOwnStepAndOmitsCredentialsOnLookup() {
        when(steps.issue("query-a", 3, "history-summary")).thenReturn(1).thenReturn(0);
        when(registry.revisionReference(31L))
                .thenReturn(
                        new RevisionReference("31", "OPENAI_CHAT_COMPLETIONS", "https://provider.invalid", "sample"));
        when(registry.invocation(31L, 9))
                .thenReturn(new ModelInvocation(
                        "31", "OPENAI_CHAT_COMPLETIONS", "https://provider.invalid", "sample", 9, "fixture"));
        var seen = new ArrayList<String>();
        var models = client((request, bytes, execution) -> {
            assertThat(request.getURI().getPath()).endsWith("/history-summary");
            assertThat(request.getHeaders().getFirst("X-Query-Step")).isEqualTo("history-summary");
            var body = new String(bytes, StandardCharsets.UTF_8);
            var lookup = request.getHeaders().getFirst("X-Query-Lookup");
            seen.add(lookup);
            if ("true".equals(lookup)) {
                // 恢复的历史依据不需要重新获取凭据。
                assertThat(body).doesNotContain("apiKey", "timeoutSeconds");
            }
            return response(HttpStatus.OK, "{\"requestIds\":[\"old\"]}");
        });

        assertThat(models.summarize(run, 31, 60, "那上周呢？", memory).requestIds()).containsExactly("old");
        var clarified = new QueryModels.Run("query-a", 3, true);
        assertThat(models.summarize(clarified, 31, 60, "那上周呢？", memory).requestIds())
                .containsExactly("old");
        assertThat(seen).containsExactly("false", "true");
        verify(registry).invocation(31L, 9);
    }

    /**
     * 检查点已提交但首次返回暂不可用时，也能用原阶段查回真实结果。
     */
    @Test
    void serviceUnavailableCanRecoverACompletedReceipt() {
        when(steps.issue("query-a", 3, "retrieve")).thenReturn(1);
        var calls = new ArrayList<String>();
        var models = client((request, bytes, execution) -> {
            var lookup = request.getHeaders().getFirst("X-Query-Lookup");
            calls.add(lookup);
            if ("false".equals(lookup)) {
                // 首次响应因检查点确认超时返回 503，回执已经写入。
                return response(HttpStatus.SERVICE_UNAVAILABLE, "{}");
            }
            return response(HttpStatus.OK, "[]");
        });

        assertThat(models.retrieve(run, "妥投量", List.of(), 9)).isEmpty();
        assertThat(calls).containsExactly("false", "true");
    }

    /**
     * 非成功、空响应或不可解析的内部调用不会产生可执行计划。
     */
    @Test
    void rejectedOrMalformedResponseFailsClosed() {
        when(steps.issue("query-a", 3, "retrieve")).thenReturn(1);
        List<ClientHttpRequestInterceptor> failures = List.of(
                (request, bytes, execution) -> response(HttpStatus.FORBIDDEN, "{}"),
                (request, bytes, execution) -> response(HttpStatus.OK, ""),
                (request, bytes, execution) -> {
                    throw new RestClientException("invalid transport");
                },
                (request, bytes, execution) -> {
                    if ("false".equals(request.getHeaders().getFirst("X-Query-Lookup"))) {
                        throw new ResourceAccessException("lost reply");
                    }
                    return response(HttpStatus.CONFLICT, "{}");
                });
        for (var handler : failures) {
            assertThatThrownBy(() -> client(handler).retrieve(run, "净能耗", List.of(), 1))
                    .isInstanceOf(QueryFailure.class)
                    .hasMessage("TECHNICAL_FAILURE");
        }
    }

    /**
     * 截获内部机器请求，保留实际 JSON 消息转换。
     */
    private QueryModels client(ClientHttpRequestInterceptor handler) {
        var beans = new StaticListableBeanFactory();
        beans.addBean(
                "test",
                RestClient.builder()
                        .baseUrl("http://127.0.0.1")
                        .requestInterceptor(handler)
                        .build());
        return new QueryModels(beans.getBeanProvider(RestClient.class), registry, steps, Set.of(10L, 11L), Set.of(10L));
    }

    /**
     * 使用可信的合成接口回包，避免在测试日志泄露正文。
     */
    private MockClientHttpResponse response(HttpStatus status, String json) {
        var response = new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }
}
