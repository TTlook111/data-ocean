package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 字段语义与一个精确快照字段的来源绑定。 */
@Data
@TableName("field_semantic_binding")
public class FieldSemanticBinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long definitionId;
    private Long datasourceId;
    private Long snapshotId;
    private Long tableMetaId;
    private Long columnMetaId;
    private String tableName;
    private String columnName;
    private String schemaFingerprint;
    private String status;
    private String statusReason;
    private String confirmationEvidence;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
