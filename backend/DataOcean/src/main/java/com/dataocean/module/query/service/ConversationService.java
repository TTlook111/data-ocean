package com.dataocean.module.query.service;

import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.entity.vo.ConversationMessagePageVO;

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
    Long saveUserMessage(Long conversationId, String content, String taskId);

    /** 原子占用会话的唯一活动轮次。 */
    void acquireTurn(Long conversationId, Long userId, Long datasourceId, String taskId);

    /** 只允许对应 taskId 释放轮次，避免迟到回调释放新轮次。 */
    void releaseTurn(Long conversationId, String taskId);

    /** 当前用户是否仍可读取该会话。 */
    boolean isVisible(Long conversationId, Long userId);

    boolean isActiveTurn(Long conversationId, String taskId);

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

    /** 按消息 ID 倒序查页，返回前端时恢复为正序。 */
    ConversationMessagePageVO listMessagePage(Long conversationId, Long userId, Long beforeMessageId, Integer pageSize);

    /**
     * 查询用户的会话列表。
     *
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID（可选筛选）
     * @return 会话列表
     */
    List<?> listConversations(Long userId, Long datasourceId);

    String deleteConversation(Long conversationId, Long userId);

    /**
     * 获取会话最近 N 条消息（内部调用，校验用户归属）。
     *
     * @param conversationId 会话 ID
     * @param userId         当前用户 ID（用于权限校验）
     * @param limit          最大条数
     * @return 消息列表（按时间正序）
     */
    List<ConversationMessageVO> getRecentMessages(Long conversationId, Long userId, int limit);

    List<ConversationMessageVO> getRecentMessagesBefore(Long conversationId, Long userId, Long beforeMessageId, int limit);

    Long getLatestMessageIdBefore(Long conversationId, Long userId, Long beforeMessageId);

    Long userMessageIdForTask(Long conversationId, Long userId, String taskId);

    /**
     * 查询指定消息之后的全部会话消息（按时间正序），用于增量生成长期摘要。
     *
     * @param conversationId 会话 ID
     * @param userId         当前用户 ID（用于权限校验）
     * @param messageId      游标；为空时查询会话全部消息
     * @return 消息列表
     */
    List<ConversationMessageVO> getMessagesAfter(Long conversationId, Long userId, Long messageId);
}
