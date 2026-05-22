package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 向量化索引任务 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供向量化索引任务表的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface VectorIndexTaskMapper extends BaseMapper<VectorIndexTask> {
}
