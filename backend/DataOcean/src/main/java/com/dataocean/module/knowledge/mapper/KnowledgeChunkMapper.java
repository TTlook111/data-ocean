package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识切片 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供知识切片表的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
}
