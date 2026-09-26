package com.dataocean.module.query.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.query.entity.Conversation;
import com.dataocean.module.query.entity.ConversationMessage;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.ConversationContextSummaryMapper;
import com.dataocean.module.query.mapper.ConversationMapper;
import com.dataocean.module.query.mapper.ConversationMessageMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 在线会话及其唯一历史证据按最后一条消息后 365 天保留。 */
@Component
@ConditionalOnProperty(prefix = "dataocean.query.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ConversationRetentionScheduler {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationContextSummaryMapper summaryMapper;
    private final QueryTaskMapper queryTaskMapper;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpiredConversations() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(365);
        List<Conversation> expired = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .in(Conversation::getStatus, "ACTIVE", "ARCHIVED")
                .lt(Conversation::getUpdatedAt, cutoff)
                .isNull(Conversation::getActiveTurnTaskId)
                .orderByAsc(Conversation::getId)
                .last("LIMIT 100"));
        for (Conversation conversation : expired) {
            Long id = conversation.getId();
            conversationMessageMapper.delete(new LambdaQueryWrapper<ConversationMessage>()
                    .eq(ConversationMessage::getConversationId, id));
            summaryMapper.delete(new LambdaQueryWrapper<com.dataocean.module.query.entity.ConversationContextSummary>()
                    .eq(com.dataocean.module.query.entity.ConversationContextSummary::getConversationId, id));
            queryTaskMapper.delete(new LambdaQueryWrapper<QueryTask>()
                    .eq(QueryTask::getConversationId, id));
            conversationMapper.deleteById(id);
        }
        if (!expired.isEmpty()) log.info("会话超过 365 天在线保留期，清理会话数={}", expired.size());
    }
}
