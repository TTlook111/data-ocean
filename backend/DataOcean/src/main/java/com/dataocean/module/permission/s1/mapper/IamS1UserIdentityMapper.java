package com.dataocean.module.permission.s1.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * S1 仅用于确认账号身份和状态的专用 Mapper，不读取任何旧授权关系。
 */
@Mapper
public interface IamS1UserIdentityMapper {

    @Select("""
            SELECT COUNT(*)
            FROM sys_user
            WHERE id = #{userId}
              AND status = 1
              AND deleted = 0
            """)
    long countEnabledUser(@Param("userId") Long userId);
}
