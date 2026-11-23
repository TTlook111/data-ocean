package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataocean.module.model.service.ModelRegistry;
import com.dataocean.module.query.entity.vo.QueryDataVO;
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
                return response(HttpStatus.OK, "{\"text\":\"妥投量 4\"}");
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
        assertThat(models.explain(
                        "query-a", 2, 11, 8, "妥投量", new QueryDataVO(List.of(), List.of(), false, "件", "", null, null)))
                .isEqualTo("妥投量 4");
        assertThat(seen)
                .containsExactly(
                        "retrieve:true",
                        "plan:false",
                        "repair-parameter-clarified:true",
                        "semantic:true",
                        "explanation-2:false");
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
