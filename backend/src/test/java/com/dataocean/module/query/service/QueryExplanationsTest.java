package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.service.impl.KnowledgeVersionUse;
import com.dataocean.module.query.mapper.QueryExplanationMapper;
import com.dataocean.module.query.mapper.QueryExplanationMapper.Explanation;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;
import com.dataocean.module.query.service.QueryProtocol.Column;
import com.dataocean.module.query.service.QueryProtocol.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * 对工业能耗和物流结果的说明验证数值符号、实际预算、授权、重试及原快照。
 */
class QueryExplanationsTest {
    /** 模拟权威平台状态和可控的模型响应。 */
    private final String id = UUID.randomUUID().toString();
    private final String command = UUID.randomUUID().toString();
    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    private final QueryRequestMapper queries = mock(QueryRequestMapper.class);
    private final QueryExplanationMapper revisions = mock(QueryExplanationMapper.class);
    private final QueryModels models = mock(QueryModels.class);
    private final AccessScopeService access = mock(AccessScopeService.class);
    private final Request row = mock(Request.class);
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private QueryExplanations service;

    /**
     * 使用原账号、已交付快照及当前规则，真实说明逻辑不依赖数据库定时。
     */
    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new IamPrincipal(7, 1, "fixture", "能耗操作员"), null));
        var actors = mock(ServiceActorPermission.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(actors.asActor(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        when(access.requireSource(7, 71)).thenReturn(new AccessScopeVO(null, List.of(), List.of()));
        when(row.id()).thenReturn(id);
        when(row.userId()).thenReturn(7L);
        when(row.sourceId()).thenReturn(71L);
        when(row.state()).thenReturn("RESULT_DELIVERED");
        when(row.executedAt()).thenReturn(now);
        when(row.bindingId()).thenReturn(12L);
        when(row.question()).thenReturn("本日净能耗");
        when(row.ruleSignature()).thenReturn(new QueryOutput().signature(List.of()));
        when(row.result()).thenReturn(json.write(new Data(
                List.of(new Column("value", "净能耗")), List.of(List.of("-4.250")), false,
                "kWh", "以实际能耗扣除回馈量", null, null)));
        when(row.remainingMillis()).thenReturn(28000);
        when(queries.lock(id)).thenReturn(row);
        when(queries.get(id)).thenReturn(row);
        when(queries.reserveExplanation(id, 22000)).thenReturn(1);
        when(models.revision("result-explanation")).thenReturn(5L);
        service = new QueryExplanations(queries, revisions, actors, mock(KnowledgeVersionUse.class),
                access, new TransactionTemplate(tx), mock(GuardMapper.class), models, json);
    }

    /**
     * 测试账号不污染其他任务。
     */
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 等价小数可展示，变号、新数字和漏掉核心值不能生成说明。
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "净能耗 -4.25 kWh|COMPLETED", "净能耗 4.250 kWh|FAILED",
        "净能耗 -4.250 kWh，另有 99|FAILED", "当前处于净回馈状态|FAILED", "' '|FAILED"
    })
    void explanationPreservesExactNumericValue(String text, String status) {
        queued();
        when(models.explain(eq(id), eq(1), eq(5L), eq(22), eq("本日净能耗"), any())).thenReturn(text);
        service.work();
        verify(revisions).claim(id, 1, 5L);
        if ("COMPLETED".equals(status)) {
            verify(revisions).finish(id, 1, status, text, null);
        } else {
            verify(revisions).finish(id, 1, status, null, "EXPLANATION_FAILED");
        }
    }

    /**
     * 目录失效、解释额度不足或结果被清理时，领取不发外部解释。
     */
    @ParameterizedTest
    @ValueSource(strings = {"resource", "reserved", "rules", "result", "missing"})
    void unavailableSnapshotDoesNotLeaveQueueWithData(String state) {
        queued();
        switch (state) {
            case "resource" -> when(row.remainingMillis()).thenReturn(1900);
            case "reserved" -> when(queries.reserveExplanation(id, 22000)).thenReturn(0);
            case "rules" -> when(row.ruleSignature()).thenReturn("other");
            case "result" -> when(row.executedAt()).thenReturn(now.minusDays(2));
            case "missing" -> when(queries.lock(id)).thenReturn(null);
            default -> throw new AssertionError(state);
        }
        service.work();
        verify(revisions).finish(id, 1, "FAILED", null, "NOT_AVAILABLE");
        verify(models, never()).explain(anyString(), anyInt(), anyLong(), anyInt(), anyString(), any());
    }

    /**
     * 重试只登记新修订，重复命令直接回读，已完成结果不再覆盖。
     */
    @Test
    void explanationRetryIsIdempotentAndBounded() {
        when(revisions.latest(id)).thenReturn(revision("FAILED", 2));
        assertThat(service.retry(id, command).revision()).isEqualTo(3);
        verify(revisions).add(id, 3, command);
        when(revisions.byCommand(id, command)).thenReturn(revision("QUEUED", 3));
        assertThat(service.retry(id, command).status()).isEqualTo("QUEUED");
        when(revisions.byCommand(id, command)).thenReturn(null);
        when(revisions.latest(id)).thenReturn(revision("COMPLETED", 3), revision("FAILED", 4));
        assertThatThrownBy(() -> service.retry(id, command)).isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.retry(id, command)).isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.retry("invalid", command)).isInstanceOf(BizIllegalException.class);
        service.initial(id);
        service.stop(id);
        service.expire();
        verify(revisions).add(id, 1, id);
        verify(revisions).stop(id);
        verify(revisions).expire();
    }

    /**
     * 只领取权威状态仍处于排队的修订。
     */
    private void queued() {
        var revision = revision("QUEUED", 1);
        when(revisions.next()).thenReturn(revision);
        when(revisions.lock(id, 1)).thenReturn(revision);
    }

    /**
     * 构造带有固定版本及原请求的内部快照。
     */
    private Explanation revision(String state, int version) {
        return new Explanation(id, version, command, state, null, null, null, now.plusMinutes(1), now, now);
    }
}
