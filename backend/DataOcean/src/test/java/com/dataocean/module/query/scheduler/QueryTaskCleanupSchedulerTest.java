package com.dataocean.module.query.scheduler;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.IamS1QueryCancellationService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.module.query.service.ConversationContextSummaryService;
import com.dataocean.module.query.service.ConversationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

class QueryTaskCleanupSchedulerTest {
    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QueryTask.class);
    }

    @Test
    void zombieTaskWritesTerminalMessageAndReleasesOnlyItsOwnTurn() {
        QueryTaskMapper tasks = mock(QueryTaskMapper.class);
        ConversationService conversations = mock(ConversationService.class);
        ConversationContextSummaryService summaries = mock(ConversationContextSummaryService.class);
        // 清理器验证持久意图，实际网络交付由取消服务的独立回归覆盖。
        IamS1QueryCancellationService cancellations = mock(IamS1QueryCancellationService.class);
        QueryTask task = QueryTask.builder().id(5L).taskId("stale-task").userId(7L).datasourceId(1L).conversationId(42L)
                .iamProtocolVersion("IAM-SIMPLE-1").permissionRevision(23L).createdAt(LocalDateTime.now().minusMinutes(5))
                .build();
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        when(tasks.update(any(), any())).thenReturn(1);
        QueryTaskCleanupScheduler scheduler = new QueryTaskCleanupScheduler(tasks, conversations, summaries, cancellations);

        scheduler.cleanupZombieTasks();

        verify(conversations).saveAssistantMessage(42L, "查询执行超时，请重新提问", "stale-task",
                "{\"taskId\":\"stale-task\",\"status\":\"TIMEOUT\"}");
        verify(conversations).releaseTurn(42L, "stale-task");
        // 超时终态与 PENDING 必须在同一个数据库 UPDATE 中，提交后才允许派发。
        ArgumentCaptor<LambdaUpdateWrapper<QueryTask>> change = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(tasks).update(isNull(), change.capture());
        assertThat(change.getValue().getSqlSet()).contains("cancellation_delivery_status");
        assertThat(change.getValue().getParamNameValuePairs().values()).contains("TIMEOUT", "PENDING");
        verify(cancellations).dispatchAfterCommit("stale-task");
        verify(summaries).refreshAsync(42L, 7L, 1L);
    }

    @Test
    void thirtyDayTaskCleanupExcludesS1HistoryEvidence() {
        QueryTaskMapper tasks = mock(QueryTaskMapper.class);
        ConversationService conversations = mock(ConversationService.class);
        ConversationContextSummaryService summaries = mock(ConversationContextSummaryService.class);
        // 历史清理不得触发取消投递或删除 S1 的待投递记录。
        IamS1QueryCancellationService cancellations = mock(IamS1QueryCancellationService.class);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        QueryTaskCleanupScheduler scheduler = new QueryTaskCleanupScheduler(tasks, conversations, summaries, cancellations);

        scheduler.cleanupOldTasks();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<QueryTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tasks).selectList(captor.capture());
        String condition = captor.getValue().getSqlSegment();
        assertThat(condition).contains("iam_protocol_version");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("IAM-SIMPLE-1");
    }

    @Test
    void completedTaskWinningTheTimeoutRaceIsNeverCancelled() {
        // 扫描后已经成功完成的任务会使 CAS=0，不能再记录或派发取消。
        QueryTaskMapper tasks = mock(QueryTaskMapper.class);
        ConversationService conversations = mock(ConversationService.class);
        ConversationContextSummaryService summaries = mock(ConversationContextSummaryService.class);
        IamS1QueryCancellationService cancellations = mock(IamS1QueryCancellationService.class);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(QueryTask.builder()
                .id(5L).taskId("completed-task").iamProtocolVersion("IAM-SIMPLE-1").build()));
        when(tasks.update(any(), any())).thenReturn(0);

        new QueryTaskCleanupScheduler(tasks, conversations, summaries, cancellations).cleanupZombieTasks();

        verify(cancellations, never()).dispatchAfterCommit(any());
        verify(conversations, never()).releaseTurn(any(), any());
    }
}
