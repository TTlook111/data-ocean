package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.SchemaSyncTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * Schema 同步任务 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供同步任务的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface SchemaSyncTaskMapper extends BaseMapper<SchemaSyncTask> {
}
