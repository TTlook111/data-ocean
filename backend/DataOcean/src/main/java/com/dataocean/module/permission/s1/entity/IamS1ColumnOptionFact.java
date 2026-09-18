package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** 已发布快照中的字段事实，含字段注释，用于授权表单的中文字段名。 */
@Data
public class IamS1ColumnOptionFact {
    private Long id;
    private Long snapshotId;
    private Long tableMetaId;
    private Long datasourceId;
    private String tableName;
    private String columnName;
    private String columnComment;
    private String dataType;
    private String governanceStatus;
}
