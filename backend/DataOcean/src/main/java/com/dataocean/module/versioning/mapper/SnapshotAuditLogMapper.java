package com.dataocean.module.versioning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.versioning.entity.SnapshotAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SnapshotAuditLogMapper extends BaseMapper<SnapshotAuditLog> {
}
