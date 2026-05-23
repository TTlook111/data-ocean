package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限 Mapper 接口。
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface PermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 查询全部权限编码。
     *
     * @return 权限编码列表
     */
    @Select("""
            SELECT permission_code
            FROM sys_permission
            ORDER BY id
            """)
    List<String> selectAllPermissionCodes();
}
