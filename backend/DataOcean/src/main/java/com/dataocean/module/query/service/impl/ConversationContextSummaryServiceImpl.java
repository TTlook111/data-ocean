package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.query.client.ConversationSummaryClient;
import com.dataocean.module.query.entity.ConversationContextSummary;
import com.dataocean.module.query.entity.dto.ConversationContextDTO;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.mapper.ConversationContextSummaryMapper;
import com.dataocean.module.query.service.ConversationContextSummaryService;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.IamS1QueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 会话长期上下文摘要服务实现。MySQL 原文始终是完整历史事实源。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationContextSummaryServiceImpl implements ConversationContextSummaryService {

    private static final int RECENT_TURN_COUNT = 5;
    private static final int RECENT_CONTEXT_MESSAGE_COUNT = RECENT_TURN_COUNT * 2;
    private static final int RECENT_LOOKBACK_MESSAGE_COUNT = RECENT_CONTEXT_MESSAGE_COUNT * 2;
    private static final String RESTRICTED_ANSWER = "历史结果当前权限无法确认，已隐藏";
    private static final Set<String> SAFE_RESULT_FIELDS = Set.of(
            "status", "sql", "sqlExplanation", "question", "usedTables", "usedColumns", "errorMessage", "masked");

    private final ConversationService conversationService;
    private final ConversationContextSummaryMapper summaryMapper;
    private final ConversationSummaryClient summaryClient;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<IamS1QueryService> queryServiceProvider;

    @Override
    public ConversationContextDTO buildQueryContext(Long conversationId, Long userId,
                                                    Long currentUserMessageId, Long permissionRevision) {
        if (conversationId == null) return emptyContext();

        ConversationContextSummary summaryEntity = findSummary(conversationId);
        List<ConversationMessageVO> recentWindow = conversationService.getRecentMessagesBefore(
                conversationId, userId, currentUserMessageId, RECENT_LOOKBACK_MESSAGE_COUNT);
        List<List<ConversationMessageVO>> recentTurns = completedTurns(recentWindow);
        if (recentTurns.size() > RECENT_TURN_COUNT) {
            recentTurns = recentTurns.subList(recentTurns.size() - RECENT_TURN_COUNT, recentTurns.size());
        }
        List<ConversationMessageVO> historyMessages = recentTurns.stream().flatMap(List::stream).toList();

        Map<String, Object> summary = Map.of();
        boolean summaryCurrent = summaryEntity != null
                && java.util.Objects.equals(summaryEntity.getPermissionRevision(), permissionRevision);
        if (summaryCurrent) summary = parseSummary(summaryEntity);
        boolean summaryUsable = summaryCurrent && !summary.isEmpty();

        if (!historyMessages.isEmpty()) {
            Long oldestRecentId = historyMessages.get(0).getId();
            Long latestOlderId = conversationService.getLatestMessageIdBefore(conversationId, userId, oldestRecentId);
            boolean summaryCoversOlderMessages = latestOlderId == null
                    || (summaryUsable && summaryEntity.getCoveredMessageId() != null
                        && summaryEntity.getCoveredMessageId() >= latestOlderId);
            if (!summaryCoversOlderMessages) {
                throw new BusinessException("会话长期记忆正在按当前权限整理，请稍后重试");
            }
        }

        List<Map<String, String>> history = new ArrayList<>();
        for (List<ConversationMessageVO> turn : recentTurns) {
            for (ConversationMessageVO message : turn) {
                String content = message.getContent() == null ? "" : message.getContent();
                if ("assistant".equals(message.getRole())) {
                    content = protectedAssistantContent(currentProtectedResult(message, userId));
                }
                history.add(Map.of("role", message.getRole(), "content", content));
            }
        }
        return ConversationContextDTO.builder()
                .summary(summary.isEmpty() ? null : summary)
                .history(history)
                .build();
    }

    @Override
    @Async("conversationSummaryExecutor")
    public void refreshAsync(Long conversationId, Long userId, Long permissionRevision) {
        if (conversationId == null) return;
        try {
            ConversationContextSummary existing = findSummary(conversationId);
            boolean samePermissionRevision = existing != null
                    && java.util.Objects.equals(existing.getPermissionRevision(), permissionRevision);
            Map<String, Object> existingSummary = samePermissionRevision ? parseSummary(existing) : Map.of();
            boolean existingSummaryUsable = samePermissionRevision && !existingSummary.isEmpty();
            Long cursor = existingSummaryUsable ? existing.getCoveredMessageId() : null;
            List<ConversationMessageVO> allAfterCursor = conversationService
                    .getMessagesAfter(conversationId, userId, cursor);
            if (allAfterCursor.isEmpty()) return;

            List<ConversationMessageVO> recent = conversationService
                    .getRecentMessages(conversationId, userId, RECENT_CONTEXT_MESSAGE_COUNT);
            Set<Long> recentIds = new HashSet<>();
            for (ConversationMessageVO message : recent) {
                if (message.getId() != null) recentIds.add(message.getId());
            }
            List<ConversationMessageVO> delta = completedTurns(allAfterCursor).stream()
                    .flatMap(List::stream)
                    .filter(message -> message.getId() != null && !recentIds.contains(message.getId()))
                    .toList();
            if (delta.isEmpty()) return;

            Map<String, Object> previousSummary = existingSummaryUsable ? existingSummary : Map.of();
            Map<String, Object> generated = summaryClient.summarize(
                    delta.stream().map(message -> toSummaryMessage(message, userId)).toList(), previousSummary);
            if (generated == null || generated.isEmpty()) {
                log.warn("Python 摘要接口返回空摘要 conversationId={}", conversationId);
                return;
            }

            Long coveredMessageId = delta.get(delta.size() - 1).getId();
            saveIfStillLatest(conversationId, generated, coveredMessageId, permissionRevision);
        } catch (Exception e) {
            // 摘要是增强能力，失败时保留已有摘要与 MySQL 原文，不影响结果落库。
            log.warn("刷新会话长期摘要失败 conversationId={}，保留原文并等待重试", conversationId, e);
        }
    }

    private com.dataocean.module.query.entity.vo.QueryTaskVO currentProtectedResult(
            ConversationMessageVO message, Long userId) {
        if (message.getTaskId() == null || message.getTaskId().isBlank()) return null;
        try {
            return queryServiceProvider.getObject().get(message.getTaskId(), userId);
        } catch (BusinessException ex) {
            return null;
        }
    }

    private String protectedAssistantContent(com.dataocean.module.query.entity.vo.QueryTaskVO result) {
        if (result == null) return RESTRICTED_ANSWER;
        if (result.getMaskedFields() != null && !result.getMaskedFields().isEmpty()) {
            return "本轮查询完成，部分字段已按当前规则脱敏";
        }
        if (result.getSqlExplanation() != null && !result.getSqlExplanation().isBlank()) return result.getSqlExplanation();
        if (result.getErrorMessage() != null && !result.getErrorMessage().isBlank()) return result.getErrorMessage();
        return "本轮查询已完成";
    }

    private List<List<ConversationMessageVO>> completedTurns(List<ConversationMessageVO> messages) {
        List<List<ConversationMessageVO>> turns = new ArrayList<>();
        ConversationMessageVO pendingUser = null;
        for (ConversationMessageVO message : messages) {
            if ("user".equals(message.getRole())) {
                pendingUser = message;
            } else if ("assistant".equals(message.getRole()) && pendingUser != null) {
                boolean sameTurn = pendingUser.getTaskId() == null || pendingUser.getTaskId().isBlank()
                        || java.util.Objects.equals(pendingUser.getTaskId(), message.getTaskId());
                if (sameTurn) turns.add(List.of(pendingUser, message));
                pendingUser = null;
            }
        }
        return turns;
    }

    private void saveIfStillLatest(Long conversationId, Map<String, Object> summary,
                                   Long coveredMessageId, Long permissionRevision) throws Exception {
        ConversationContextSummary current = findSummary(conversationId);
        if (current != null && java.util.Objects.equals(current.getPermissionRevision(), permissionRevision)
                && !parseSummary(current).isEmpty()
                && current.getCoveredMessageId() != null && coveredMessageId != null
                && current.getCoveredMessageId() >= coveredMessageId) {
            log.debug("丢弃过期会话摘要 conversationId={} coveredMessageId={}", conversationId, coveredMessageId);
            return;
        }

        String summaryJson = objectMapper.writeValueAsString(summary);
        if (current == null) {
            ConversationContextSummary entity = ConversationContextSummary.builder()
                    .conversationId(conversationId).summaryJson(summaryJson).coveredMessageId(coveredMessageId)
                    .summaryVersion(1).permissionRevision(permissionRevision)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            summaryMapper.insert(entity);
            return;
        }

        current.setSummaryJson(summaryJson);
        current.setCoveredMessageId(coveredMessageId);
        current.setPermissionRevision(permissionRevision);
        current.setSummaryVersion((current.getSummaryVersion() == null ? 0 : current.getSummaryVersion()) + 1);
        current.setUpdatedAt(LocalDateTime.now());
        summaryMapper.updateById(current);
    }

    private ConversationContextSummary findSummary(Long conversationId) {
        return summaryMapper.selectOne(new LambdaQueryWrapper<ConversationContextSummary>()
                .eq(ConversationContextSummary::getConversationId, conversationId));
    }

    private Map<String, Object> parseSummary(ConversationContextSummary entity) {
        if (entity == null || entity.getSummaryJson() == null || entity.getSummaryJson().isBlank()) return Map.of();
        try {
            return objectMapper.readValue(entity.getSummaryJson(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析会话摘要失败 conversationId={}", entity.getConversationId(), e);
            return Map.of();
        }
    }

    private Map<String, Object> toSummaryMessage(ConversationMessageVO message, Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageId", message.getId());
        result.put("role", message.getRole());
        var safeTask = "assistant".equals(message.getRole()) ? currentProtectedResult(message, userId) : null;
        boolean safeAnswer = !"assistant".equals(message.getRole()) || safeTask != null;
        result.put("content", "assistant".equals(message.getRole())
                ? protectedAssistantContent(safeTask)
                : (message.getContent() == null ? "" : message.getContent()));
        if (safeAnswer && safeTask != null) {
            Map<String, Object> resultFacts = new LinkedHashMap<>();
            resultFacts.put("status", safeTask.getStatus());
            resultFacts.put("usedTables", safeTask.getUsedTables());
            resultFacts.put("usedColumns", safeTask.getUsedColumns());
            if (safeTask.getMaskedFields() != null && !safeTask.getMaskedFields().isEmpty()) {
                resultFacts.put("masked", true);
            } else {
                resultFacts.put("sqlExplanation", safeTask.getSqlExplanation());
                resultFacts.put("question", safeTask.getQuestion());
                if (Boolean.TRUE.equals(safeTask.getCanViewSql())) resultFacts.put("sql", safeTask.getSql());
            }
            if (safeTask.getErrorMessage() != null) resultFacts.put("errorMessage", safeTask.getErrorMessage());
            resultFacts.keySet().removeIf(key -> !SAFE_RESULT_FIELDS.contains(key));
            result.put("queryFacts", resultFacts);
        }
        return result;
    }

    private ConversationContextDTO emptyContext() {
        return ConversationContextDTO.builder().summary(null).history(List.of()).build();
    }
}
