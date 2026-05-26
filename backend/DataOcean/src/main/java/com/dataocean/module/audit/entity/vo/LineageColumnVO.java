package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 字段级血缘视图对象
 */
@Data
public class LineageColumnVO {
    private Long queryTaskId;
    private String sourceTable;
    private String sourceColumn;
    private String expression;
    private String aliasName;
    private String question;
    private LocalDateTime createdAt;
}
