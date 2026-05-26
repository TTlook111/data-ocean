package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.QueryAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询审计日志 Mapper 接口
 */
@Mapper
public interface QueryAuditLogMapper extends BaseMapper<QueryAuditLog> {
}
