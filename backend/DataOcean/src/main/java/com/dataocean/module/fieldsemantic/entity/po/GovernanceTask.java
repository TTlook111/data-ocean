package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 平台内治理持久化记录。 */
@Data
@TableName("governance_task")
public class GovernanceTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private Long snapshotId;
    private Long columnMetaId;
    private Long businessRevisionId;
    private String targetKey;
    private String tableName;
    private String columnName;
    private String origin;
    private Long baseRevisionId;
    private Integer expectedVersion;
    private String status;
    private String executionToken;
    private Integer roundNo;
    private String reason;
    private Long candidateId;
    private Long createdBy;
    private Integer attempts;
    private java.time.LocalDateTime lockedUntil;
    private java.time.LocalDateTime updatedAt;
}
