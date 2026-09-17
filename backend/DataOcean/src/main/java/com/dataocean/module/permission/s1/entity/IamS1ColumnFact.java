package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** S1 资源准入和条件类型校验所需的字段事实。 */
@Data
public class IamS1ColumnFact {
    private Long id;
    private Long snapshotId;
    private Long tableMetaId;
    private Long datasourceId;
    private String tableName;
    private String columnName;
    private String dataType;
    private String governanceStatus;
}
