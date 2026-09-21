package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 权限变更审计实体。 */
@Data
@TableName("iam_s1_audit_event")
public class IamS1AuditEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventType;
    private Long operatorId;
    private String targetType;
    private Long targetId;
    private String beforeSummary;
    private String afterSummary;
    private String reason;
    private String executionId;
    private Integer success;
    private String failureReason;
    private LocalDateTime createdAt;
}
