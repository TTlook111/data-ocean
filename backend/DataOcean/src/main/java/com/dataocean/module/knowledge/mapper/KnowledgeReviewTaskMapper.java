package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.knowledge.entity.KnowledgeReviewTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识审核任务 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供知识审核任务表的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface KnowledgeReviewTaskMapper extends BaseMapper<KnowledgeReviewTask> {
}
