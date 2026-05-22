package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档版本 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供知识文档版本表的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface KnowledgeDocVersionMapper extends BaseMapper<KnowledgeDocVersion> {
}
