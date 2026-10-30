package com.dataocean.module.permission.s1.entity.vo;

/**
 * 已发布快照中可授权的表。只有治理状态允许的表可以进入授权表单。
 */
public record IamS1TableOptionVO(Long datasourceId,
                                 Long metadataSnapshotId,
                                 String tableName,
                                 String tableComment,
                                 String governanceStatus,
                                 boolean selectable,
                                 int columnCount) {
}
