package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityIssueVO {

    private Long id;
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
