package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 平台内治理持久化记录。 */
@Data
@TableName("governance_field_usage")
public class GovernanceFieldUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private Long datasourceId;
    private Long snapshotId;
    private Long columnMetaId;
    private Long definitionId;
    private Long revisionId;
    private String fieldName;
    private String profileJson;
    private java.time.LocalDateTime createdAt;
}
