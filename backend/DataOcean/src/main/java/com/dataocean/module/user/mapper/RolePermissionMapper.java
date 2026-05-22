package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-权限关联数据访问层接口。
 * <p>
 * 对应数据库表 sys_role_permission（角色与权限的多对多中间表），
 * 继承 MyBatis-Plus BaseMapper 提供基础 CRUD 能力。
 * </p>
 *
 * @author DataOcean
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<SysRolePermission> {
}
