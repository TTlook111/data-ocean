package com.dataocean.module.fieldtag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldtag.entity.FieldConfidence;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字段可信度 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供字段可信度的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface FieldConfidenceMapper extends BaseMapper<FieldConfidence> {
}
