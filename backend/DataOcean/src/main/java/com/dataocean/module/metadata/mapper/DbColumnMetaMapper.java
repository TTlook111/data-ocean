package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据库字段元数据 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供字段元数据的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface DbColumnMetaMapper extends BaseMapper<DbColumnMeta> {
}
