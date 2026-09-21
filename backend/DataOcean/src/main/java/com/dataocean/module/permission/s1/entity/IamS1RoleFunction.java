package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 角色功能关系实体。 */
@Data
@TableName("iam_s1_role_function")
public class IamS1RoleFunction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roleId;
    private Long functionId;
    private Long createdBy;
    private LocalDateTime createdAt;
}
