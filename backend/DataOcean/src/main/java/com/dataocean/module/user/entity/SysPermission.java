package com.dataocean.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 系统权限实体类，对应数据库表 sys_permission。
 * <p>
 * 定义平台中的细粒度权限点，通过 sys_role_permission 关联到角色，
 * 用于接口级别的访问控制。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限编码，唯一标识（如 user:create、datasource:delete） */
    private String permissionCode;

    /** 权限显示名称 */
    private String permissionName;

    /** 所属模块（如 user、datasource、metadata） */
    private String module;

    /** 权限描述说明 */
    private String description;
}
