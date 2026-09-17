package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** S1 资源准入所需的表事实。 */
@Data
public class IamS1TableFact {
    private Long id;
    private Long snapshotId;
    private Long datasourceId;
    private String tableName;
    private String governanceStatus;
}
