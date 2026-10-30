package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 平台内治理持久化记录。 */
@Data
@TableName("governance_feedback")
public class GovernanceFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private Long userId;
    private Long datasourceId;
    private String feedbackType;
    private String reasonCode;
    private String comment;
    private Long columnMetaId;
    private Long definitionId;
    private Long revisionId;
    private String status;
    private String followupStatus;
    private Long reviewedBy;
    private String reviewNote;
    private String resolutionJson;
    private Integer rowVersion;
    private java.time.LocalDateTime updatedAt;
}
