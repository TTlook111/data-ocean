package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** S1 资源准入所需的已发布快照事实。 */
@Data
public class IamS1MetadataSnapshotFact {
    private Long id;
    private Long datasourceId;
    private String status;
}
