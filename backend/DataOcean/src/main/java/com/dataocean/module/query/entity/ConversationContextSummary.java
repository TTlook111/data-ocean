package com.dataocean.module.query.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话长期上下文摘要。
 * <p>
 * 摘要属于 Java 侧的持久化上下文，不是 Python 的会话状态。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("conversation_context_summary")
public class ConversationContextSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    @TableField("summary_json")
    private String summaryJson;

    private Long coveredMessageId;

    private Integer summaryVersion;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
