package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReviewRecordVO {

    private Long id;
    private String targetType;
    private String tableName;
    private String columnName;
    private String action;
    private String oldStatus;
    private String newStatus;
    private String operatorName;
    private String remark;
    private LocalDateTime createdAt;
}
