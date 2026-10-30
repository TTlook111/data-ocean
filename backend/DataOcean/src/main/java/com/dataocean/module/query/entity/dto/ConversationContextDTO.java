package com.dataocean.module.query.entity.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Java 组装后发送给 Python Agent 的会话上下文。
 * 不包含 conversationId，Python 不负责会话持久化和历史查询。
 */
@Data
@Builder
public class ConversationContextDTO {

    /** 长期结构化摘要 */
    private Map<String, Object> summary;

    /** 最近的完整对话消息 */
    private List<Map<String, String>> history;
}
