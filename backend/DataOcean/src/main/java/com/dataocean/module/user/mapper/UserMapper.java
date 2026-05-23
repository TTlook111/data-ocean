package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户数据访问层接口。
 * <p>
 * 对应数据库表 sys_user，继承 MyBatis-Plus BaseMapper 提供基础 CRUD 能力，
 * 并扩展自定义 SQL 查询用户权限编码。
 * </p>
 *
 * @author DataOcean
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface UserMapper extends BaseMapper<SysUser> {

    /**
     * 根据用户 ID 查询该用户拥有的所有权限编码（去重）。
     * <p>
     * 通过 用户-角色-角色权限-权限 四表联查，仅返回启用状态角色所关联的权限编码。
     * </p>
     *
     * @param userId 用户 ID
     * @return 权限编码列表（去重后）
     */
    @Select("""
            SELECT DISTINCT p.permission_code
            FROM sys_user_role ur
            JOIN sys_role_permission rp ON rp.role_id = ur.role_id
            JOIN sys_permission p ON p.id = rp.permission_id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
            """)
    List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);
}
