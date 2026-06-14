package com.dataocean.module.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.entity.PermissionChangeLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限变更审计日志 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface PermissionChangeLogMapper extends BaseMapper<PermissionChangeLog> {
}
