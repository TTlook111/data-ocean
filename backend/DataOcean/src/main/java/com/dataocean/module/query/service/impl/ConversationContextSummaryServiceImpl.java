package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.query.client.ConversationSummaryClient;
import com.dataocean.module.query.entity.ConversationContextSummary;
import com.dataocean.module.query.entity.dto.ConversationContextDTO;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.mapper.ConversationContextSummaryMapper;
import com.dataocean.module.query.service.ConversationContextSummaryService;
import com.dataocean.module.query.service.ConversationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 会话长期上下文摘要服务实现。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationContextSummaryServiceImpl implements ConversationContextSummaryService {

    /** 摘要存在时，保留最近三轮完整对话作为即时上下文。 */
    private static final int RECENT_CONTEXT_MESSAGE_COUNT = 6;

    private static final Set<String> SAFE_RESULT_FIELDS = Set.of(
            "status", "sql", "sqlExplanation", "rewrittenQuery",
            "usedTables", "usedColumns", "error");

    private final ConversationService conversationService;
    private final ConversationContextSummaryMapper summaryMapper;
    private final ConversationSummaryClient summaryClient;
    private final ObjectMapper objectMapper;

    @Override
    public ConversationContextDTO buildQueryContext(Long conversationId, Long userId) {
        if (conversationId == null) {
            return emptyContext();
        }

        try {
            ConversationContextSummary summaryEntity = findSummary(conversationId);
            Map<String, Object> summary = parseSummary(summaryEntity);
            List<ConversationMessageVO> messages = summary.isEmpty()
                    ? new ArrayList<>(conversationService.getMessagesAfter(conversationId, userId, null))
                    : new ArrayList<>(conversationService.getRecentMessages(
                            conversationId, userId, RECENT_CONTEXT_MESSAGE_COUNT + 1));

            // QueryController 已先保存当前用户消息，这条消息由 question 单独传给 Python，不能重复放入历史。
            if (!messages.isEmpty() && "user".equals(messages.get(messages.size() - 1).getRole())) {
                messages.remove(messages.size() - 1);
            }

            List<Map<String, String>> history = new ArrayList<>();
            for (ConversationMessageVO message : messages) {
                Map<String, String> turn = new LinkedHashMap<>();
                turn.put("role", message.getRole());
                turn.put("content", message.getContent() == null ? "" : message.getContent());
                history.add(turn);
            }
            return ConversationContextDTO.builder()
                    .summary(summary.isEmpty() ? null : summary)
                    .history(history)
                    .build();
        } catch (Exception e) {
            log.warn("构建会话上下文失败 conversationId={}，降级为空摘要和空历史", conversationId, e);
            return emptyContext();
        }
    }

    @Override
    @Async("conversationSummaryExecutor")
    public void refreshAsync(Long conversationId, Long userId) {
        if (conversationId == null) {
            return;
        }

        try {
            ConversationContextSummary existing = findSummary(conversationId);
            Long cursor = existing == null ? null : existing.getCoveredMessageId();
            List<ConversationMessageVO> messages = conversationService
                    .getMessagesAfter(conversationId, userId, cursor);
            if (messages.isEmpty()) {
                return;
            }

            // 只把已经从即时上下文中退出的消息交给摘要 LLM；最近三轮继续原样保留。
            List<ConversationMessageVO> recent = conversationService
                    .getRecentMessages(conversationId, userId, RECENT_CONTEXT_MESSAGE_COUNT);
            Set<Long> recentIds = new HashSet<>();
            for (ConversationMessageVO message : recent) {
                if (message.getId() != null) {
                    recentIds.add(message.getId());
                }
            }
            List<ConversationMessageVO> delta = messages.stream()
                    .filter(message -> message.getId() != null && !recentIds.contains(message.getId()))
                    .toList();
            if (delta.isEmpty()) {
                return;
            }

            Map<String, Object> previousSummary = parseSummary(existing);
            Map<String, Object> generated = summaryClient.summarize(
                    delta.stream().map(this::toSummaryMessage).toList(), previousSummary);
            if (generated == null || generated.isEmpty()) {
                log.warn("Python 摘要接口返回空摘要 conversationId={}", conversationId);
                return;
            }

            Long coveredMessageId = delta.get(delta.size() - 1).getId();
            saveIfStillLatest(conversationId, generated, coveredMessageId);
        } catch (Exception e) {
            // 摘要是增强能力，失败时保留已有摘要，绝不影响当前查询结果。
            log.warn("刷新会话长期摘要失败 conversationId={}，不影响查询主链路", conversationId, e);
        }
    }

    private void saveIfStillLatest(Long conversationId,
                                   Map<String, Object> summary,
                                   Long coveredMessageId) throws Exception {
        ConversationContextSummary current = findSummary(conversationId);
        if (current != null && current.getCoveredMessageId() != null
                && coveredMessageId != null
                && current.getCoveredMessageId() >= coveredMessageId) {
            log.debug("丢弃过期会话摘要 conversationId={} coveredMessageId={}", conversationId, coveredMessageId);
            return;
        }

        String summaryJson = objectMapper.writeValueAsString(summary);
        if (current == null) {
            ConversationContextSummary entity = ConversationContextSummary.builder()
                    .conversationId(conversationId)
                    .summaryJson(summaryJson)
                    .coveredMessageId(coveredMessageId)
                    .summaryVersion(1)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            summaryMapper.insert(entity);
            return;
        }

        current.setSummaryJson(summaryJson);
        current.setCoveredMessageId(coveredMessageId);
        current.setSummaryVersion((current.getSummaryVersion() == null ? 0 : current.getSummaryVersion()) + 1);
        current.setUpdatedAt(LocalDateTime.now());
        summaryMapper.updateById(current);
    }

    private ConversationContextSummary findSummary(Long conversationId) {
        return summaryMapper.selectOne(new LambdaQueryWrapper<ConversationContextSummary>()
                .eq(ConversationContextSummary::getConversationId, conversationId));
    }

    private Map<String, Object> parseSummary(ConversationContextSummary entity) {
        if (entity == null || entity.getSummaryJson() == null || entity.getSummaryJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(entity.getSummaryJson(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析会话摘要失败 conversationId={}", entity.getConversationId(), e);
            return Map.of();
        }
    }

    private Map<String, Object> toSummaryMessage(ConversationMessageVO message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageId", message.getId());
        result.put("role", message.getRole());
        result.put("content", message.getContent() == null ? "" : message.getContent());

        // 助手消息的 metadata 包含完整结果，但摘要只接收可复用的查询事实，不发送结果明细。
        if ("assistant".equals(message.getRole()) && message.getMetadata() != null) {
            try {
                JsonNode metadata = objectMapper.readTree(message.getMetadata());
                Map<String, Object> resultFacts = new LinkedHashMap<>();
                for (String field : SAFE_RESULT_FIELDS) {
                    JsonNode value = metadata.get(field);
                    if (value != null && !value.isNull()) {
                        resultFacts.put(field, objectMapper.convertValue(value, Object.class));
                    }
                }
                if (!resultFacts.isEmpty()) {
                    result.put("queryFacts", resultFacts);
                }
            } catch (Exception e) {
                log.debug("助手消息 metadata 不是有效 JSON messageId={}", message.getId());
            }
        }
        return result;
    }

    private ConversationContextDTO emptyContext() {
        return ConversationContextDTO.builder()
                .summary(null)
                .history(List.of())
                .build();
    }
}
