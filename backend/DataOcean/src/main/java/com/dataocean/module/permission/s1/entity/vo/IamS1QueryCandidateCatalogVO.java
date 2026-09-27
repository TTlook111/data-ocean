package com.dataocean.module.permission.s1.entity.vo;

import java.util.List;

/** Java 从当前快照与 IAM-SIMPLE-1 Resolver 完整构造的候选资源目录。 */
public record IamS1QueryCandidateCatalogVO(
        Long datasourceId,
        Long activeMetadataSnapshotId,
        Long permissionRevision,
        List<IamS1QueryCandidateTableVO> tables) {
}
