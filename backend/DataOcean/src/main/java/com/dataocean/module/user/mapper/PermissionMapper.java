package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PermissionMapper extends BaseMapper<SysPermission> {

    @Select("""
            SELECT permission_code
            FROM sys_permission
            ORDER BY id ASC
            """)
    List<String> selectAllPermissionCodes();
}
