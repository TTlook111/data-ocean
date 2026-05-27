package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色数据访问层接口。
 * <p>
 * 对应数据库表 sys_role，继承 MyBatis-Plus BaseMapper 提供基础 CRUD 能力，
 * 并扩展自定义 SQL 查询指定用户所拥有的角色列表。
 * </p>
 *
 * @author DataOcean
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface RoleMapper extends BaseMapper<SysRole> {

    /**
     * 根据用户 ID 查询该用户关联的所有角色。
     * <p>
     * 通过 sys_user_role 中间表联查 sys_role，按角色 ID 升序排列。
     * </p>
     *
     * @param userId 用户 ID
     * @return 该用户关联的角色列表
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
}
