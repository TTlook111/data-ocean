package com.dataocean.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色-权限关联实体类，对应数据库表 sys_role_permission。
 * <p>
 * 维护角色与权限的多对多关系，一个角色可拥有多个权限，
 * 一个权限也可被多个角色引用。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色ID，关联 sys_role.id */
    private Long roleId;

    /** 权限ID，关联 sys_permission.id */
    private Long permissionId;
}
