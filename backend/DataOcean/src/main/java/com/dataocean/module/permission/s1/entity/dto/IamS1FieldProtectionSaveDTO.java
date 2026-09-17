package com.dataocean.module.permission.s1.entity.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** S1 字段保护写入请求。保护不授予字段查询权。 */
@Getter
@Setter
@NoArgsConstructor
public class IamS1FieldProtectionSaveDTO {
    private String protocolVersion;
    private Long datasourceId;
    private Long metadataSnapshotId;
    private String tableName;
    private Long columnMetaId;
    private String columnName;
    private String protectionLevel;
    private String maskPolicy;
    private String status;
    private String reason;
}
