package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-角色关联数据访问层接口。
 * <p>
 * 对应数据库表 sys_user_role（用户与角色的多对多中间表），
 * 继承 MyBatis-Plus BaseMapper 提供基础 CRUD 能力。
 * </p>
 *
 * @author DataOcean
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<SysUserRole> {
}
