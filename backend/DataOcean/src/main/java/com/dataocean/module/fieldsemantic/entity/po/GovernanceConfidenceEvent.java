package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 平台内治理持久化记录。 */
@Data
@TableName("governance_confidence_event")
public class GovernanceConfidenceEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long feedbackId;
    private Integer feedbackVersion;
    private Long definitionId;
    private Long revisionId;
    private String direction;
    private Integer active;
    private String evidenceKey;
    private Integer evidencePolicyVersion;
    private Long operatorId;
    private java.time.LocalDateTime createdAt;
}
