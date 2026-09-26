package com.dataocean.module.query.scheduler;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
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
        QueryTask task = QueryTask.builder().id(5L).taskId("stale-task").userId(7L).conversationId(42L)
                .iamProtocolVersion("IAM-SIMPLE-1").permissionRevision(23L).createdAt(LocalDateTime.now().minusMinutes(5))
                .build();
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        when(tasks.update(any(), any())).thenReturn(1);
        QueryTaskCleanupScheduler scheduler = new QueryTaskCleanupScheduler(tasks, conversations, summaries);

        scheduler.cleanupZombieTasks();

        verify(conversations).saveAssistantMessage(42L, "查询执行超时，请重新提问", "stale-task",
                "{\"taskId\":\"stale-task\",\"status\":\"TIMEOUT\"}");
        verify(conversations).releaseTurn(42L, "stale-task");
        verify(summaries).refreshAsync(42L, 7L, 23L);
    }

    @Test
    void thirtyDayTaskCleanupExcludesS1HistoryEvidence() {
        QueryTaskMapper tasks = mock(QueryTaskMapper.class);
        ConversationService conversations = mock(ConversationService.class);
        ConversationContextSummaryService summaries = mock(ConversationContextSummaryService.class);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        QueryTaskCleanupScheduler scheduler = new QueryTaskCleanupScheduler(tasks, conversations, summaries);

        scheduler.cleanupOldTasks();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<QueryTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tasks).selectList(captor.capture());
        String condition = captor.getValue().getSqlSegment();
        assertThat(condition).contains("iam_protocol_version");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("IAM-SIMPLE-1");
    }
}
