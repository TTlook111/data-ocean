package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色数据访问层接口。
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface RoleMapper extends BaseMapper<SysRole> {

    /**
     * 根据用户 ID 查询该用户关联的所有启用角色。
     */
    @Select("""
            SELECT r.*
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
            ORDER BY r.id
            """)
    List<SysRole> selectByUserId(@Param("userId") Long userId);

    /**
     * 查询指定角色下的用户成员。
     */
    @Select("""
            SELECT u.*
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            WHERE ur.role_id = #{roleId}
              AND u.deleted = 0
            ORDER BY u.id
            """)
    List<SysUser> selectUsersByRoleId(@Param("roleId") Long roleId);

    /**
     * 统计指定角色下仍处于正常状态的用户数量。
     */
    @Select("""
            SELECT COUNT(1)
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            WHERE ur.role_id = #{roleId}
              AND u.status = 1
              AND u.deleted = 0
            """)
    long countActiveUsersByRoleId(@Param("roleId") Long roleId);
}
