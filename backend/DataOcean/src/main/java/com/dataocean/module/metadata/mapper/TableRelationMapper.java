package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.TableRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 表关系 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供表关系数据的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface TableRelationMapper extends BaseMapper<TableRelation> {
}
