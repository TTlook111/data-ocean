package com.dataocean.module.query.service;

import com.dataocean.module.query.entity.vo.ConversationMessageVO;

import java.util.List;

/**
 * 会话服务接口
 */
public interface ConversationService {

    /**
     * 获取或创建会话。
     * <p>
     * 如果 conversationId 不为空则返回该会话 ID，
     * 否则创建新会话并返回 ID。
     * </p>
     *
     * @param userId         用户 ID
     * @param datasourceId   数据源 ID
     * @param conversationId 已有会话 ID（可选）
     * @param firstQuestion  首条问题（用于生成标题）
     * @return 会话 ID
     */
    Long getOrCreateConversation(Long userId, Long datasourceId, Long conversationId, String firstQuestion);

    /**
     * 保存用户消息。
     *
     * @param conversationId 会话 ID
     * @param content        消息内容
     */
    void saveUserMessage(Long conversationId, String content);

    /**
     * 保存助手消息（含查询结果元数据）。
     *
     * @param conversationId 会话 ID
     * @param content        消息内容（口径说明或错误提示）
     * @param taskId         关联的任务 ID
     * @param metadata       附加元数据 JSON
     */
    void saveAssistantMessage(Long conversationId, String content, String taskId, String metadata);

    /**
     * 查询会话的所有消息。
     *
     * @param conversationId 会话 ID
     * @param userId         当前用户 ID（用于权限校验）
     * @param page           页码
     * @param pageSize       每页大小
     * @return 消息列表
     */
    List<ConversationMessageVO> listMessages(Long conversationId, Long userId, Integer page, Integer pageSize);

    /**
     * 查询用户的会话列表。
     *
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID（可选筛选）
     * @return 会话列表
     */
    List<?> listConversations(Long userId, Long datasourceId);
}
