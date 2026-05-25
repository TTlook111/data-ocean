package com.dataocean.module.query.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息 VO
 */
@Data
@Builder
public class ConversationMessageVO {

    private Long id;
    private String role;
    private String content;
    private String taskId;
    private String metadata;
    private LocalDateTime createdAt;
}
