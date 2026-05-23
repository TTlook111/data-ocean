package com.dataocean.module.versioning.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快照审计日志视图对象。
 */
@Data
public class SnapshotAuditLogVO {

    private Long id;
    private String action;
    private String oldStatus;
    private String newStatus;
    private String operatorName;
    private String reason;
    private LocalDateTime createdAt;
}
