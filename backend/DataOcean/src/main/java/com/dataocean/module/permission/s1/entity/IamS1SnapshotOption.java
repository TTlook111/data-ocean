package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** 已发布元数据快照的最小事实，供授权表单选择快照使用。 */
@Data
public class IamS1SnapshotOption {
    private Long id;
    private Long datasourceId;
    private Integer snapshotVersion;
    private String status;
}
