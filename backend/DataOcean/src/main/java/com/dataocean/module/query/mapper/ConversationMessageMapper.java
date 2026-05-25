package com.dataocean.module.query.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.query.entity.ConversationMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话消息 Mapper
 */
@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {
}
