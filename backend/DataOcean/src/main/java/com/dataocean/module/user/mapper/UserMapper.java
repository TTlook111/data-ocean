package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户数据访问层接口。
 * <p>
 * 对应数据库表 sys_user，继承 MyBatis-Plus BaseMapper 提供基础 CRUD 能力，
 * 并扩展用户身份相关查询。
 * </p>
 *
 * @author DataOcean
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface UserMapper extends BaseMapper<SysUser> {

    /**
     * 查询用户所属部门 ID
     *
     * @param userId 用户 ID
     * @return 部门 ID，无部门时返回 null
     */
    @Select("SELECT department_id FROM sys_user WHERE id = #{userId} AND deleted = 0")
    Long selectDepartmentIdByUserId(@Param("userId") Long userId);
}
