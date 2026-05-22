package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.SchemaChangeEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * Schema 变更事件 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供变更事件的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface SchemaChangeEventMapper extends BaseMapper<SchemaChangeEvent> {
}
