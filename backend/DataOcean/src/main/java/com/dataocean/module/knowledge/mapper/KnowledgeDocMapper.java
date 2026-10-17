package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.knowledge.entity.po.KnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供知识文档表的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDoc> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM knowledge_doc WHERE id=#{id} AND deleted=0 FOR UPDATE")
    KnowledgeDoc selectForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);
}
