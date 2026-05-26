package com.dataocean.module.fieldtag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字段可信度变更事件 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供可信度变更事件的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface FieldConfidenceEventMapper extends BaseMapper<FieldConfidenceEvent> {
}
