package com.dataocean.module.query.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.query.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话会话 Mapper
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
