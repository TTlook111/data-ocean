package com.dataocean.module.query.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.query.client.ConversationSummaryClient;
import com.dataocean.module.query.entity.ConversationContextSummary;
import com.dataocean.module.query.entity.dto.ConversationContextDTO;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.mapper.ConversationContextSummaryMapper;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.IamS1QueryService;
import com.dataocean.module.permission.s1.entity.vo.IamS1QueryCandidateCatalogVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1QueryCandidateColumnVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1QueryCandidateTableVO;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.dataocean.module.permission.s1.service.IamS1UserResourceService;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private IamS1UserResourceService userResourceService;
    private SchemaSnapshotService schemaSnapshotService;
    private ConversationContextSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        summaryMapper = mock(ConversationContextSummaryMapper.class);
        summaryClient = mock(ConversationSummaryClient.class);
        queryProvider = mock(ObjectProvider.class);
        queryService = mock(IamS1QueryService.class);
        userResourceService = mock(IamS1UserResourceService.class);
        schemaSnapshotService = mock(SchemaSnapshotService.class);
        when(queryProvider.getObject()).thenReturn(queryService);
        service = new ConversationContextSummaryServiceImpl(
                conversationService, summaryMapper, summaryClient, new ObjectMapper(), queryProvider,
                userResourceService, schemaSnapshotService);
    }

    @Test
    void contextContainsOnlyTheFiveMostRecentCompletedTurnsAndCurrentRevisionSummary() {
        when(summaryMapper.selectOne(any())).thenReturn(summary(10L, 100L, currentCatalog(100L, true)));
        when(conversationService.getRecentMessagesBefore(42L, 7L, 21L, 20))
                .thenReturn(turnMessages(10));
        when(conversationService.getLatestMessageIdBefore(42L, 7L, 11L)).thenReturn(10L);
        when(queryService.get(any(), eq(7L))).thenReturn(com.dataocean.module.query.entity.vo.QueryTaskVO.builder().build());

        ConversationContextDTO context = service.buildQueryContext(42L, 7L, 21L, currentCatalog(100L, true));

        assertThat(context.getHistory()).hasSize(10);
        assertThat(context.getHistory().get(0)).containsEntry("content", "question-6");
        assertThat(context.getHistory().get(9)).containsEntry("role", "assistant");
        assertThat(context.getSummary()).containsEntry("memory", "older");
    }

    @Test
    void stalePermissionSummaryIsNeverSentAndUncoveredHistoryBlocksSubmission() {
        when(summaryMapper.selectOne(any())).thenReturn(summary(10L, 100L, currentCatalog(100L, true)));
        when(conversationService.getRecentMessagesBefore(42L, 7L, 21L, 20))
                .thenReturn(turnMessages(10));
        when(conversationService.getLatestMessageIdBefore(42L, 7L, 11L)).thenReturn(10L);

        assertThatThrownBy(() -> service.buildQueryContext(42L, 7L, 21L, currentCatalog(101L, true)))
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

        assertThatThrownBy(() -> service.buildQueryContext(42L, 7L, 21L, currentCatalog(100L, true)))
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

        ConversationContextDTO context = service.buildQueryContext(42L, 7L, 5L, currentCatalog(101L, true));

        assertThat(context.getHistory()).extracting(item -> item.get("content"))
                .containsExactly("question-1", "本轮查询已完成", "question-2", "历史结果当前权限无法确认，已隐藏");
    }

    @Test
    void expiredGrantWithUnchangedRevisionInvalidatesSummaryAndFiltersRecentAnswer() {
        IamS1QueryCandidateCatalogVO previouslyAuthorized = currentCatalog(100L, true);
        IamS1QueryCandidateCatalogVO afterExpiry = currentCatalog(100L, false);
        when(summaryMapper.selectOne(any())).thenReturn(summary(10L, 100L, previouslyAuthorized));
        when(conversationService.getRecentMessagesBefore(42L, 7L, 13L, 20)).thenReturn(List.of(
                message(11L, "user", "task-expired", "上次查订单金额"),
                message(12L, "assistant", "task-expired", "旧权限下的答案")));
        when(conversationService.getLatestMessageIdBefore(42L, 7L, 11L)).thenReturn(null);
        when(queryService.get("task-expired", 7L)).thenThrow(new BusinessException("授权已到期"));

        ConversationContextDTO context = service.buildQueryContext(42L, 7L, 13L, afterExpiry);

        assertThat(context.getSummary()).isNull();
        assertThat(context.getHistory()).extracting(item -> item.get("content"))
                .containsExactly("上次查订单金额", "历史结果当前权限无法确认，已隐藏");
    }

    @Test
    void expiredGrantWithUnchangedRevisionRebuildsSummaryWithoutPreviousSummary() throws Exception {
        IamS1QueryCandidateCatalogVO previouslyAuthorized = currentCatalog(100L, true);
        IamS1QueryCandidateCatalogVO afterExpiry = currentCatalog(100L, false);
        ConversationContextSummary existing = summary(2L, 100L, previouslyAuthorized);
        when(summaryMapper.selectOne(any())).thenReturn(existing);
        com.dataocean.module.metadata.entity.MetadataSnapshot metadata =
                new com.dataocean.module.metadata.entity.MetadataSnapshot();
        metadata.setId(88L);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata);
        when(userResourceService.candidateCatalog(7L, 1L, 88L)).thenReturn(afterExpiry);
        when(conversationService.getMessagesAfter(42L, 7L, null)).thenReturn(turnMessages(6));
        when(conversationService.getRecentMessages(42L, 7L, 10))
                .thenReturn(turnMessages(6).subList(2, 12));
        when(queryService.get("task-1", 7L)).thenThrow(new BusinessException("授权已到期"));
        when(summaryClient.summarize(any(), any())).thenReturn(Map.of("memory", "fresh"));

        service.refreshAsync(42L, 7L, 1L);

        org.mockito.ArgumentCaptor<Map<String, Object>> previousSummary =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.ArgumentCaptor<List<Map<String, Object>>> messages =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(summaryClient).summarize(messages.capture(), previousSummary.capture());
        assertThat(previousSummary.getValue()).isEmpty();
        assertThat(messages.getValue()).extracting(item -> item.get("content"))
                .contains("历史结果当前权限无法确认，已隐藏");
        org.mockito.ArgumentCaptor<ConversationContextSummary> savedSummary =
                org.mockito.ArgumentCaptor.forClass(ConversationContextSummary.class);
        verify(summaryMapper).updateById(savedSummary.capture());
        assertThat(savedSummary.getValue().getPermissionScopeFingerprint())
                .isEqualTo(service.permissionScopeFingerprint(afterExpiry));
    }

    private ConversationContextSummary summary(Long coveredId, Long revision,
                                                IamS1QueryCandidateCatalogVO catalog) {
        return ConversationContextSummary.builder().conversationId(42L)
                .summaryJson("{\"memory\":\"older\"}")
                .coveredMessageId(coveredId).permissionRevision(revision)
                .permissionScopeFingerprint(service.permissionScopeFingerprint(catalog))
                .summaryVersion(1).build();
    }

    private IamS1QueryCandidateCatalogVO currentCatalog(Long revision, boolean hasExpiredGrant) {
        List<IamS1QueryCandidateTableVO> tables = hasExpiredGrant ? List.of(new IamS1QueryCandidateTableVO(
                "orders", "订单", "NORMAL", List.of(new IamS1QueryCandidateColumnVO(
                        11L, "amount", "金额", "DECIMAL", "NORMAL", "NORMAL", null,
                        List.of(IamS1ColumnUsage.PROJECTION), List.of(
                        new com.dataocean.module.permission.s1.entity.vo.IamS1QueryCandidateGrantSourceVO(
                                77L, "USER", 7L, "直接授权", "SELF", "DIRECT", 77L,
                                "2026-09-01T00:00:00", "2026-09-26T00:00:00", List.of("amount"), null)))))) : List.of();
        return new IamS1QueryCandidateCatalogVO(1L, 88L, revision, tables);
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
