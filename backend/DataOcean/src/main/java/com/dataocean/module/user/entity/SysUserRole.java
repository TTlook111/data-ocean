package com.dataocean.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-角色关联实体类，对应数据库表 sys_user_role。
 * <p>
 * 维护用户与角色的多对多关系，一个用户可拥有多个角色，
 * 一个角色也可分配给多个用户。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID，关联 sys_user.id */
    private Long userId;

    /** 角色ID，关联 sys_role.id */
    private Long roleId;
}
