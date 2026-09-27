package com.dataocean.module.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Idempotency and protected-result record for one SQL candidate. Never stores row-condition bind values. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("query_attempt")
public class QueryAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String attemptId;
    private Integer attemptNo;
    private String sqlHash;
    private String safeSql;
    private String status;
    private Long permissionRevision;
    private Long activeMetadataSnapshotId;
    private String resourceRequestJson;
    private String executionSnapshotJson;
    private String protectedData;
    private String protectedColumns;
    private String sourceTrace;
    private String maskedFields;
    private String usedTables;
    private String usedColumns;
    private Integer rowCount;
    private Integer executionTimeMs;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
