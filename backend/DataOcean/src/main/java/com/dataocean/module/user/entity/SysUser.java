package com.dataocean.module.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体类，对应数据库表 sys_user。
 * <p>
 * 存储平台所有用户的基本信息、认证凭据和账号状态，
 * 是用户模块的核心实体。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 用户状态：正常 */
    public static final int STATUS_NORMAL = 1;
    /** 用户状态：禁用（管理员手动禁用） */
    public static final int STATUS_DISABLED = 2;
    /** 用户状态：锁定（登录失败次数过多自动锁定） */
    public static final int STATUS_LOCKED = 3;

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名，唯一标识 */
    private String username;

    /** 密码哈希值（BCrypt 加密存储） */
    private String passwordHash;

    /** 是否已修改初始密码：0-未修改，1-已修改 */
    private Integer passwordChanged;

    /** 用户真实姓名 */
    private String realName;

    /** 电子邮箱 */
    private String email;

    /** 手机号码 */
    private String phone;

    /** 所属部门ID，关联 sys_department.id */
    private Long departmentId;

    /** 账号状态：1-正常，2-禁用，3-锁定 */
    private Integer status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    /** 创建时间，插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间，插入和更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除标记：0-未删除，1-已删除 */
    @TableLogic
    private Integer deleted;
}
