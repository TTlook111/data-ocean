package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.query.entity.Conversation;
import com.dataocean.module.query.entity.ConversationMessage;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.mapper.ConversationMapper;
import com.dataocean.module.query.mapper.ConversationMessageMapper;
import com.dataocean.module.query.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            if (existing != null && existing.getUserId().equals(userId)) {
                return existing.getId();
            }
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
    public void saveUserMessage(Long conversationId, String content) {
        ConversationMessage message = ConversationMessage.builder()
                .conversationId(conversationId)
                .role("user")
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
        conversationMessageMapper.insert(message);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void saveAssistantMessage(Long conversationId, String content, String taskId, String metadata) {
        ConversationMessage message = ConversationMessage.builder()
                .conversationId(conversationId)
                .role("assistant")
                .content(content)
                .taskId(taskId)
                .metadata(metadata)
                .createdAt(LocalDateTime.now())
                .build();
        conversationMessageMapper.insert(message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConversationMessageVO> listMessages(Long conversationId, Long userId, Integer page, Integer pageSize) {
        // 校验会话归属当前用户
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException("会话不存在或无权访问");
        }
        Page<ConversationMessage> pageResult = conversationMessageMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .orderByAsc(ConversationMessage::getCreatedAt));
        return pageResult.getRecords().stream().map(this::toVO).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<?> listConversations(Long userId, Long datasourceId) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getStatus, "ACTIVE")
                .eq(datasourceId != null, Conversation::getDatasourceId, datasourceId)
                .orderByDesc(Conversation::getUpdatedAt);
        return conversationMapper.selectList(wrapper);
    }

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
}
