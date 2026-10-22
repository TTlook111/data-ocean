package com.dataocean.module.knowledge.entity.vo;

import lombok.Data;

@Data
public class FieldSemanticUsageVO {
    private String status;
    private String message;
    private String buildId;
    private Long sourceSnapshotId;
    private Long chunkId;
    private Long docId;
    private Integer versionNo;
}
