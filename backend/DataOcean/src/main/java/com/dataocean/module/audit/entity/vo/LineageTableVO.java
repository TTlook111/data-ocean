package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 表级血缘视图对象
 */
@Data
public class LineageTableVO {
    private Long queryTaskId;
    private String sourceTable;
    private String targetName;
    private String relationType;
    private String question;
    private LocalDateTime createdAt;
}
