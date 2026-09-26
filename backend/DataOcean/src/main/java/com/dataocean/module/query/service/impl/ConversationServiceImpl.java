package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.query.entity.Conversation;
import com.dataocean.module.query.entity.ConversationMessage;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.entity.vo.ConversationMessagePageVO;
import com.dataocean.module.query.mapper.ConversationMapper;
import com.dataocean.module.query.mapper.ConversationMessageMapper;
import com.dataocean.module.query.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话服务实现类。
 * <p>
 * 管理对话会话的创建、消息持久化和查询。
 * 每次查询请求保存 user message，收到结果后保存 assistant message。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public Long getOrCreateConversation(Long userId, Long datasourceId, Long conversationId, String firstQuestion) {
        if (conversationId != null) {
            Conversation existing = conversationMapper.selectById(conversationId);
            if (existing == null || !existing.getUserId().equals(userId)
                    || !existing.getDatasourceId().equals(datasourceId)) {
                throw new BusinessException("会话不存在或无权访问");
            }
            if (!"ACTIVE".equals(existing.getStatus())) {
                throw new BusinessException("已归档或已删除的会话不能继续提问");
            }
            if (existing.getUpdatedAt() != null && existing.getUpdatedAt().isBefore(LocalDateTime.now().minusDays(365))) {
                throw new BusinessException("会话已超过 365 天在线保留期");
            }
            return existing.getId();
        }
        // 创建新会话，标题取问题前 50 字
        String title = firstQuestion.length() > 50 ? firstQuestion.substring(0, 50) : firstQuestion;
        Conversation conversation = Conversation.builder()
                .userId(userId)
                .datasourceId(datasourceId)
                .title(title)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        conversationMapper.insert(conversation);
        log.info("创建新会话 conversationId={} userId={}", conversation.getId(), userId);
        return conversation.getId();
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public Long saveUserMessage(Long conversationId, String content, String taskId) {
        ConversationMessage existing = conversationMessageMapper.selectOne(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .eq(ConversationMessage::getTaskId, taskId)
                .eq(ConversationMessage::getRole, "user")
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();
        ConversationMessage message = ConversationMessage.builder()
                .conversationId(conversationId)
                .role("user")
                .content(content)
                .taskId(taskId)
                .createdAt(LocalDateTime.now())
                .build();
        conversationMessageMapper.insert(message);
        touchConversation(conversationId);
        return message.getId();
    }

    @Override
    public void acquireTurn(Long conversationId, Long userId, Long datasourceId, String taskId) {
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getDatasourceId, datasourceId)
                .eq(Conversation::getStatus, "ACTIVE")
                .and(wrapper -> wrapper.isNull(Conversation::getActiveTurnTaskId)
                        .or().eq(Conversation::getActiveTurnTaskId, taskId))
                .set(Conversation::getActiveTurnTaskId, taskId));
        if (updated != 1) {
            throw new BusinessException("该会话正在处理另一个问题，请稍后重试");
        }
    }

    @Override
    public void releaseTurn(Long conversationId, String taskId) {
        if (conversationId == null || taskId == null) return;
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getActiveTurnTaskId, taskId)
                .set(Conversation::getActiveTurnTaskId, null));
    }

    @Override
    public boolean isVisible(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        return conversation != null && userId != null && userId.equals(conversation.getUserId())
                && !"DELETED".equals(conversation.getStatus())
                && (conversation.getUpdatedAt() == null
                    || !conversation.getUpdatedAt().isBefore(LocalDateTime.now().minusDays(365)));
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void saveAssistantMessage(Long conversationId, String content, String taskId, String metadata) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || "DELETED".equals(conversation.getStatus())) return;
        ConversationMessage existing = conversationMessageMapper.selectOne(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .eq(ConversationMessage::getTaskId, taskId)
                .eq(ConversationMessage::getRole, "assistant")
                .last("LIMIT 1"));
        if (existing != null) return;
        ConversationMessage message = ConversationMessage.builder()
                .conversationId(conversationId)
                .role("assistant")
                .content(content)
                .taskId(taskId)
                .metadata(metadata)
                .createdAt(LocalDateTime.now())
                .build();
        conversationMessageMapper.insert(message);
        touchConversation(conversationId);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConversationMessageVO> listMessages(Long conversationId, Long userId, Integer page, Integer pageSize) {
        // 校验会话归属当前用户
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (!isVisible(conversationId, userId)) {
            throw new BusinessException("会话不存在或无权访问");
        }
        Page<ConversationMessage> pageResult = conversationMessageMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .orderByAsc(ConversationMessage::getId));
        return pageResult.getRecords().stream().map(this::toVO).toList();
    }

    @Override
    public ConversationMessagePageVO listMessagePage(Long conversationId, Long userId,
                                                     Long beforeMessageId, Integer pageSize) {
        if (!isVisible(conversationId, userId)) throw new BusinessException("会话不存在或无权访问");
        int size = Math.max(1, Math.min(pageSize == null ? 50 : pageSize, 100));
        LambdaQueryWrapper<ConversationMessage> wrapper = new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId);
        if (beforeMessageId != null) wrapper.lt(ConversationMessage::getId, beforeMessageId);
        List<ConversationMessage> descending = conversationMessageMapper.selectList(wrapper
                .orderByDesc(ConversationMessage::getId).last("LIMIT " + (size + 1)));
        boolean hasMore = descending.size() > size;
        if (hasMore) descending.remove(descending.size() - 1);
        java.util.Collections.reverse(descending);
        List<ConversationMessageVO> items = descending.stream().map(this::toVO).toList();
        Long cursor = items.isEmpty() ? null : items.get(0).getId();
        return ConversationMessagePageVO.builder().items(items).nextBeforeMessageId(cursor).hasMore(hasMore).build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<?> listConversations(Long userId, Long datasourceId) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getStatus, "ACTIVE")
                .ge(Conversation::getUpdatedAt, LocalDateTime.now().minusDays(365))
                .eq(datasourceId != null, Conversation::getDatasourceId, datasourceId)
                .orderByDesc(Conversation::getUpdatedAt);
        return conversationMapper.selectList(wrapper);
    }

    /**
     * 归档会话。
     * <p>
     * 将指定会话状态更新为 ARCHIVED，使其不再在会话列表中显示。
     * 归档前校验会话存在性和归属权，只能归档自己的会话。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId         当前用户ID（用于权限校验）
     * @throws BusinessException 如果会话不存在或不属于当前用户
     */
    @Transactional
    @Override
    public String deleteConversation(Long conversationId, Long userId) {
        // 校验会话存在性和归属权
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)
                || "DELETED".equals(conversation.getStatus())) {
            throw new BusinessException("会话不存在或无权访问");
        }
        String activeTaskId = conversation.getActiveTurnTaskId();
        conversation.setStatus("DELETED");
        conversation.setActiveTurnTaskId(null);
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
        return activeTaskId;
    }

    /**
     * 更新会话的 updated_at 时间戳。
     */
    private void touchConversation(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null && !"DELETED".equals(conversation.getStatus())) {
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
    }

    /**
     * 将会话消息实体转换为前端展示 VO。
     */
    private ConversationMessageVO toVO(ConversationMessage msg) {
        return ConversationMessageVO.builder()
                .id(msg.getId())
                .role(msg.getRole())
                .content(msg.getContent())
                .taskId(msg.getTaskId())
                .metadata(msg.getMetadata())
                .createdAt(msg.getCreatedAt())
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConversationMessageVO> getRecentMessages(Long conversationId, Long userId, int limit) {
        if (!isVisible(conversationId, userId)) {
            throw new BusinessException("会话不存在或无权访问");
        }
        List<ConversationMessage> messages = conversationMessageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .orderByDesc(ConversationMessage::getId)
                        .last("LIMIT " + limit));
        // 反转为正序
        java.util.Collections.reverse(messages);
        return messages.stream().map(this::toVO).toList();
    }

    @Override
    public List<ConversationMessageVO> getRecentMessagesBefore(Long conversationId, Long userId,
                                                               Long beforeMessageId, int limit) {
        if (!isVisible(conversationId, userId)) throw new BusinessException("会话不存在或无权访问");
        LambdaQueryWrapper<ConversationMessage> wrapper = new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .lt(beforeMessageId != null, ConversationMessage::getId, beforeMessageId)
                .orderByDesc(ConversationMessage::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100)));
        List<ConversationMessage> messages = conversationMessageMapper.selectList(wrapper);
        java.util.Collections.reverse(messages);
        return messages.stream().map(this::toVO).toList();
    }

    @Override
    public Long getLatestMessageIdBefore(Long conversationId, Long userId, Long beforeMessageId) {
        if (!isVisible(conversationId, userId)) throw new BusinessException("会话不存在或无权访问");
        ConversationMessage latest = conversationMessageMapper.selectOne(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .lt(beforeMessageId != null, ConversationMessage::getId, beforeMessageId)
                .orderByDesc(ConversationMessage::getId)
                .select(ConversationMessage::getId)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getId();
    }

    @Override
    public List<ConversationMessageVO> getMessagesAfter(Long conversationId, Long userId, Long messageId) {
        if (!isVisible(conversationId, userId)) {
            throw new BusinessException("会话不存在或无权访问");
        }
        LambdaQueryWrapper<ConversationMessage> wrapper = new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .orderByAsc(ConversationMessage::getId);
        if (messageId != null) {
            wrapper.gt(ConversationMessage::getId, messageId);
        }
        return conversationMessageMapper.selectList(wrapper).stream().map(this::toVO).toList();
    }
}
