package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 权限事实修订实体。 */
@Data
@TableName("iam_s1_permission_revision")
public class IamS1PermissionRevision {
    @TableId(value = "revision_no", type = IdType.AUTO)
    private Long revisionNo;
    private String targetType;
    private Long targetId;
    private String changeType;
    private Long operatorId;
    private String reason;
    private LocalDateTime createdAt;
}
