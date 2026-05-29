package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审计日志视图对象
 */
@Data
public class AuditLogVO {
    private Long id;
    private Long queryTaskId;
    private Long userId;
    private String username;
    private Long datasourceId;
    private String datasourceName;
    private String question;
    private String sqlText;
    private String usedTables;
    private String usedFields;
    private String promptVersions;
    private Integer executionTimeMs;
    private Integer rowCount;
    private Boolean isSuccess;
    private String errorMessage;
    private Boolean isSlow;
    private String userFeedback;
    private LocalDateTime createdAt;
}
