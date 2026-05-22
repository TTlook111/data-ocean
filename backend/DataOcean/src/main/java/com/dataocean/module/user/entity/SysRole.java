package com.dataocean.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统角色实体类，对应数据库表 sys_role。
 * <p>
 * 定义平台中的角色信息，角色通过 sys_role_permission 关联权限，
 * 通过 sys_user_role 关联用户，实现 RBAC 权限模型。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("sys_role")
public class SysRole {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码，唯一标识（如 ADMIN、DATA_ANALYST） */
    private String roleCode;

    /** 角色显示名称 */
    private String roleName;

    /** 角色描述说明 */
    private String description;

    /** 角色状态：1-启用，0-禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
