package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 字段保护事实。 */
@Data
@TableName("iam_s1_field_protection")
public class IamS1FieldProtection {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String protocolVersion;
    private Long datasourceId;
    private Long metadataSnapshotId;
    private String tableName;
    private Long columnMetaId;
    private String columnName;
    private String protectionLevel;
    private String maskPolicy;
    private String status;
    private Long revisionNo;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
