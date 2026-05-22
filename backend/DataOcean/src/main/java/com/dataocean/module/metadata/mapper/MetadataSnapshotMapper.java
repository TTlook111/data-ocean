package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 元数据快照 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供元数据快照表的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface MetadataSnapshotMapper extends BaseMapper<MetadataSnapshot> {
}
