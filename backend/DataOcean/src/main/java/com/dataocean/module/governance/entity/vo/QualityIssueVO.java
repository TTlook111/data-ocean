package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量问题列表视图对象。
 */
@Data
public class QualityIssueVO {

    private Long id;
    private Long snapshotId;
    private Long datasourceId;
    private String datasourceName;
    private String dimension;
    private String severity;
    private String tableName;
    private String columnName;
    private String issueDescription;
    private String suggestion;
    private String status;
    private Long assigneeId;
    private String assigneeName;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
