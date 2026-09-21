package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** 已发布快照中的表事实，含治理状态与表注释（表注释来自已审核元数据，不猜测业务含义）。 */
@Data
public class IamS1TableOptionFact {
    private Long id;
    private Long snapshotId;
    private Long datasourceId;
    private String tableName;
    private String tableComment;
    private String governanceStatus;
}
