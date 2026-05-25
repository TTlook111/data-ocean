package com.dataocean.module.query.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话消息实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("conversation_message")
public class ConversationMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID */
    private Long conversationId;

    /** 消息角色：user/assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 关联的查询任务 ID */
    private String taskId;

    /** 附加元数据 JSON */
    private String metadata;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
