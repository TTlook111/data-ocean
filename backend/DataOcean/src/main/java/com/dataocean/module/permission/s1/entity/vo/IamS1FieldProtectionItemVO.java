package com.dataocean.module.permission.s1.entity.vo;

import java.time.LocalDateTime;

/**
 * 字段保护条目。允许查询不等于允许看原值：NORMAL 正常显示、HIDDEN 不进 Schema/RAG、MASKED 返回保护值。
 */
public record IamS1FieldProtectionItemVO(Long id,
                                         Long datasourceId,
                                         String datasourceName,
                                         Long metadataSnapshotId,
                                         String tableName,
                                         Long columnMetaId,
                                         String columnName,
                                         String protectionLevel,
                                         String protectionLevelName,
                                         String maskPolicy,
                                         String maskPolicyName,
                                         String status,
                                         Long revisionNo,
                                         LocalDateTime updatedAt) {
}
