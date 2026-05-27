package com.dataocean.module.permission.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行列级策略视图对象
 *
 * @author dataocean
 */
@Data
public class AccessPolicyVO {

    private Long id;
    private Long datasourceId;
    private String subjectType;
    private Long subjectId;
    /** 主体名称 */
    private String subjectName;
    private String tableName;
    private String columnName;
    private String accessType;
    private String maskStrategy;
    private String rowFilterExpression;
    private LocalDateTime createdAt;
}
