package com.dataocean.module.query.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.query.entity.po.Conversation;
import com.dataocean.module.query.entity.po.ConversationMessage;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.ConversationContextSummaryMapper;
import com.dataocean.module.query.mapper.ConversationMapper;
import com.dataocean.module.query.mapper.ConversationMessageMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.mapper.QueryAttemptMapper;
import com.dataocean.module.query.mapper.QueryModelCallMapper;
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
    private final QueryAttemptMapper queryAttemptMapper;
    private final QueryModelCallMapper queryModelCallMapper;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpiredConversations() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(365);
        // 在 LIMIT 前排除已有待投递意图，避免最旧会话长期占满候选；锁后仍复核并发新增意图。
        List<Conversation> expired = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .in(Conversation::getStatus, "ACTIVE", "ARCHIVED")
                .lt(Conversation::getUpdatedAt, cutoff)
                .isNull(Conversation::getActiveTurnTaskId)
                .notExists("SELECT 1 FROM query_task pending_cancellation "
                        + "WHERE pending_cancellation.conversation_id = conversation.id "
                        + "AND pending_cancellation.cancellation_delivery_status = {0}", "PENDING")
                .orderByAsc(Conversation::getId)
                .last("LIMIT 100"));
        int purged = 0;
        for (Conversation conversation : expired) {
            Long id = conversation.getId();
            // 清理前锁定任务当前状态；尚未接收的取消意图不能随历史保留期被静默删除。
            List<QueryTask> tasks = queryTaskMapper.selectList(new LambdaQueryWrapper<QueryTask>()
                    .eq(QueryTask::getConversationId, id)
                    .select(QueryTask::getTaskId, QueryTask::getCancellationDeliveryStatus)
                    .orderByAsc(QueryTask::getId)
                    .last("FOR UPDATE"));
            if (tasks.stream().anyMatch(task -> "PENDING".equals(task.getCancellationDeliveryStatus()))) continue;
            conversationMessageMapper.delete(new LambdaQueryWrapper<ConversationMessage>()
                    .eq(ConversationMessage::getConversationId, id));
            summaryMapper.delete(new LambdaQueryWrapper<com.dataocean.module.query.entity.po.ConversationContextSummary>()
                    .eq(com.dataocean.module.query.entity.po.ConversationContextSummary::getConversationId, id));
            // 使用已锁定的任务集合清理关联证据，保持原有保留期和删除顺序。
            List<String> taskIds = tasks.stream().map(QueryTask::getTaskId).toList();
            if (!taskIds.isEmpty()) {
                queryAttemptMapper.delete(new LambdaQueryWrapper<com.dataocean.module.query.entity.po.QueryAttempt>()
                        .in(com.dataocean.module.query.entity.po.QueryAttempt::getTaskId, taskIds));
                queryModelCallMapper.delete(new LambdaQueryWrapper<com.dataocean.module.query.entity.po.QueryModelCall>()
                        .in(com.dataocean.module.query.entity.po.QueryModelCall::getTaskId, taskIds));
                queryTaskMapper.delete(new LambdaQueryWrapper<QueryTask>()
                        .in(QueryTask::getTaskId, taskIds));
            }
            conversationMapper.deleteById(id);
            purged++;
        }
        // 因待投递意图保留的会话不计入已清理数量。
        if (purged > 0) log.info("会话超过 365 天在线保留期，清理会话数={}", purged);
    }
}
