package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.MetadataChangeEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 元数据变更事件 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface MetadataChangeEventMapper extends BaseMapper<MetadataChangeEvent> {
}
