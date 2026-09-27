package com.dataocean.module.query.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 消息 ID 游标分页结果；items 始终按消息 ID 正序。 */
@Data
@Builder
public class ConversationMessagePageVO {
    private List<ConversationMessageVO> items;
    private Long nextBeforeMessageId;
    private boolean hasMore;
}
