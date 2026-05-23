package com.dataocean.module.versioning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快照审计日志实体。
 * <p>
 * 记录快照发布、撤回、过期和状态流转等版本生命周期操作。
 * </p>
 */
@Data
@TableName("snapshot_audit_log")
public class SnapshotAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private Long datasourceId;
    private String action;
    private String oldStatus;
    private String newStatus;
    private Long operatorId;
    private String reason;
    private String contextJson;
    private LocalDateTime createdAt;
}
