package com.dataocean.module.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_DISABLED = 2;
    public static final int STATUS_LOCKED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private Integer passwordChanged;
    private String realName;
    private String email;
    private String phone;
    private Long departmentId;
    private Integer status;
    private LocalDateTime lastLoginAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
