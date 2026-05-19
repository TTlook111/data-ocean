package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DbColumnMetaMapper extends BaseMapper<DbColumnMeta> {
}
