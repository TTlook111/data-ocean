package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.query.entity.QueryModelCall;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.entity.dto.IamS1ModelCallBudgetDTO;
import com.dataocean.module.query.mapper.QueryModelCallMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.ConversationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamS1QueryBudgetServiceImplTest {
    private final QueryTaskMapper tasks = mock(QueryTaskMapper.class);
    private final QueryModelCallMapper calls = mock(QueryModelCallMapper.class);
    private final IamS1DataAuthorizationResolver data = mock(IamS1DataAuthorizationResolver.class);
    private final IamS1AuthorizationResolver function = mock(IamS1AuthorizationResolver.class);
    private final com.dataocean.module.metadata.service.SchemaSnapshotService snapshots =
            mock(com.dataocean.module.metadata.service.SchemaSnapshotService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final IamS1QueryBudgetServiceImpl service = new IamS1QueryBudgetServiceImpl(
            tasks, calls, data, function, snapshots, conversations);
    private QueryTask task;

    @BeforeAll
    static void initMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, QueryTask.class);
        TableInfoHelper.initTableInfo(assistant, QueryModelCall.class);
    }

    @BeforeEach
    void setUp() {
        task = QueryTask.builder().id(1L).taskId("task-1").userId(7L).datasourceId(5L)
                .conversationId(42L).iamProtocolVersion("IAM-SIMPLE-1").status("PROCESSING")
                .permissionRevision(9L).activeMetadataSnapshotId(88L).llmCallCount(0)
                .estimatedAiCostCny(BigDecimal.ZERO).createdAt(LocalDateTime.now()).build();
        when(tasks.selectByTaskIdForUpdate("task-1")).thenReturn(task);
        when(data.currentPermissionRevision()).thenReturn(9L);
        when(function.hasGlobalFunction(7L, "query:use")).thenReturn(true);
        when(conversations.isVisible(42L, 7L)).thenReturn(true);
        when(conversations.isActiveTurn(42L, "task-1")).thenReturn(true);
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(88L);
        when(snapshots.getPublishedSnapshot(5L)).thenReturn(snapshot);
        when(calls.selectForUpdate("task-1", "call-1")).thenReturn(null);
    }

    @Test
    void reservationsDurablyCountCallsAndUseConservativeOutputCost() {
        Map<String, Object> reserved = service.reserve("task-1", request(10_000, 1_024));

        assertThat(reserved.get("allowed")).isEqualTo(true);
        assertThat(task.getLlmCallCount()).isEqualTo(1);
        assertThat(task.getEstimatedAiCostCny()).isEqualByComparingTo(new BigDecimal("0.003036"));
        verify(calls).insert(any(QueryModelCall.class));
        verify(tasks).updateById(task);
    }

    @Test
    void ninthModelCallIsRejectedBeforeProviderDispatch() {
        task.setLlmCallCount(8);

        Map<String, Object> denied = service.reserve("task-1", request(1_000, 1_024));

        assertThat(denied.get("allowed")).isEqualTo(false);
        assertThat(task.getEstimatedAiCostCny()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void authorizedEmbeddingCostUsesItsOwnTwoCallBudget() {
        IamS1ModelCallBudgetDTO embedding = request("text-embedding-v4", "rag_embedding", 500, 0);

        Map<String, Object> result = service.reserve("task-1", embedding);

        assertThat(result.get("allowed")).isEqualTo(true);
        assertThat(task.getLlmCallCount()).isZero();
        assertThat(task.getEmbeddingCallCount()).isEqualTo(1);
        assertThat(task.getEstimatedAiCostCny()).isEqualByComparingTo(new BigDecimal("0.000250"));
    }

    @Test
    void settlementReplacesWorstCaseReservationWithActualUsage() {
        QueryModelCall call = QueryModelCall.builder().id(3L).taskId("task-1").callId("call-1")
                .modelName("qwen-flash").status("RESERVED").reservedCostCny(new BigDecimal("0.003036"))
                .inputTokens(10_000).outputTokens(1_024).build();
        task.setLlmCallCount(1);
        task.setEstimatedAiCostCny(new BigDecimal("0.003036"));
        when(calls.selectForUpdate("task-1", "call-1")).thenReturn(call);
        IamS1ModelCallBudgetDTO request = request(10_000, 500);
        request.setUsageReported(true);

        Map<String, Object> settled = service.settle("task-1", request);

        assertThat(settled.get("allowed")).isEqualTo(true);
        assertThat(task.getEstimatedAiCostCny()).isEqualByComparingTo(new BigDecimal("0.002250"));
        assertThat(call.getStatus()).isEqualTo("COMPLETED");
    }

    private IamS1ModelCallBudgetDTO request(int input, int output) {
        return request("qwen-flash", "sql_generation", input, output);
    }

    private IamS1ModelCallBudgetDTO request(String model, String node, int input, int output) {
        IamS1ModelCallBudgetDTO request = new IamS1ModelCallBudgetDTO();
        request.setCallId("call-1");
        request.setNodeName(node);
        request.setModelName(model);
        request.setInputTokens(input);
        request.setOutputTokens(output);
        return request;
    }
}
