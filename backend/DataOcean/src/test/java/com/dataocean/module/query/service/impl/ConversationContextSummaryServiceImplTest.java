package com.dataocean.module.query.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.query.client.ConversationSummaryClient;
import com.dataocean.module.query.entity.ConversationContextSummary;
import com.dataocean.module.query.entity.dto.ConversationContextDTO;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.mapper.ConversationContextSummaryMapper;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.IamS1QueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationContextSummaryServiceImplTest {
    private ConversationService conversationService;
    private ConversationContextSummaryMapper summaryMapper;
    private ConversationSummaryClient summaryClient;
    private ObjectProvider<IamS1QueryService> queryProvider;
    private IamS1QueryService queryService;
    private ConversationContextSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        summaryMapper = mock(ConversationContextSummaryMapper.class);
        summaryClient = mock(ConversationSummaryClient.class);
        queryProvider = mock(ObjectProvider.class);
        queryService = mock(IamS1QueryService.class);
        when(queryProvider.getObject()).thenReturn(queryService);
        service = new ConversationContextSummaryServiceImpl(
                conversationService, summaryMapper, summaryClient, new ObjectMapper(), queryProvider);
    }

    @Test
    void contextContainsOnlyTheFiveMostRecentCompletedTurnsAndCurrentRevisionSummary() {
        when(summaryMapper.selectOne(any())).thenReturn(summary(10L, 100L));
        when(conversationService.getRecentMessagesBefore(42L, 7L, 21L, 20))
                .thenReturn(turnMessages(10));
        when(conversationService.getLatestMessageIdBefore(42L, 7L, 11L)).thenReturn(10L);
        when(queryService.get(any(), eq(7L))).thenReturn(com.dataocean.module.query.entity.vo.QueryTaskVO.builder().build());

        ConversationContextDTO context = service.buildQueryContext(42L, 7L, 21L, 100L);

        assertThat(context.getHistory()).hasSize(10);
        assertThat(context.getHistory().get(0)).containsEntry("content", "question-6");
        assertThat(context.getHistory().get(9)).containsEntry("role", "assistant");
        assertThat(context.getSummary()).containsEntry("memory", "older");
    }

    @Test
    void stalePermissionSummaryIsNeverSentAndUncoveredHistoryBlocksSubmission() {
        when(summaryMapper.selectOne(any())).thenReturn(summary(10L, 100L));
        when(conversationService.getRecentMessagesBefore(42L, 7L, 21L, 20))
                .thenReturn(turnMessages(10));
        when(conversationService.getLatestMessageIdBefore(42L, 7L, 11L)).thenReturn(10L);

        assertThatThrownBy(() -> service.buildQueryContext(42L, 7L, 21L, 101L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("按当前权限整理");
        verify(queryService, org.mockito.Mockito.never()).get(any(), anyLong());
    }

    @Test
    void corruptSummaryDoesNotSilentlyMarkOlderTurnsAsCovered() {
        when(summaryMapper.selectOne(any())).thenReturn(ConversationContextSummary.builder()
                .conversationId(42L).summaryJson("not-json").coveredMessageId(10L)
                .permissionRevision(100L).summaryVersion(1).build());
        when(conversationService.getRecentMessagesBefore(42L, 7L, 21L, 20))
                .thenReturn(turnMessages(10));
        when(conversationService.getLatestMessageIdBefore(42L, 7L, 11L)).thenReturn(10L);

        assertThatThrownBy(() -> service.buildQueryContext(42L, 7L, 21L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("按当前权限整理");
        verify(queryService, org.mockito.Mockito.never()).get(any(), anyLong());
    }

    @Test
    void currentPermissionFailureReplacesOnlyThatHistoricalAnswerWithPlaceholder() {
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(conversationService.getRecentMessagesBefore(42L, 7L, 5L, 20))
                .thenReturn(List.of(
                        message(1L, "user", "task-1", "question-1"),
                        message(2L, "assistant", "task-1", "answer-1"),
                        message(3L, "user", "task-2", "question-2"),
                        message(4L, "assistant", "task-2", "answer-2")));
        when(conversationService.getLatestMessageIdBefore(42L, 7L, 1L)).thenReturn(null);
        when(queryService.get("task-1", 7L)).thenReturn(com.dataocean.module.query.entity.vo.QueryTaskVO.builder().build());
        when(queryService.get("task-2", 7L)).thenThrow(new BusinessException("permission revoked"));

        ConversationContextDTO context = service.buildQueryContext(42L, 7L, 5L, 101L);

        assertThat(context.getHistory()).extracting(item -> item.get("content"))
                .containsExactly("question-1", "本轮查询已完成", "question-2", "历史结果当前权限无法确认，已隐藏");
    }

    private ConversationContextSummary summary(Long coveredId, Long revision) {
        return ConversationContextSummary.builder().conversationId(42L).summaryJson("{\"memory\":\"older\"}")
                .coveredMessageId(coveredId).permissionRevision(revision).summaryVersion(1).build();
    }

    private List<ConversationMessageVO> turnMessages(int count) {
        List<ConversationMessageVO> messages = new ArrayList<>();
        for (int turn = 1; turn <= count; turn++) {
            long userId = turn * 2L - 1;
            long assistantId = turn * 2L;
            String taskId = "task-" + turn;
            messages.add(message(userId, "user", taskId, "question-" + turn));
            messages.add(message(assistantId, "assistant", taskId, "answer-" + turn));
        }
        return messages;
    }

    private ConversationMessageVO message(Long id, String role, String taskId, String content) {
        return ConversationMessageVO.builder().id(id).role(role).taskId(taskId).content(content).build();
    }
}
