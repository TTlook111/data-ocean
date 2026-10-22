package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 候选仅供人工采纳，不代表已审核语义事实。 */
@Data
@TableName("field_semantic_candidate")
public class FieldSemanticCandidate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long definitionId;
    private Long bindingId;
    private Long snapshotId;
    private Long baseRevisionId;
    private Integer baseRowVersion;
    private String status;
    private String inputHash;
    private String inputJson;
    private String businessName;
    private String meaning;
    private String unit;
    private String currency;
    private String enumSemantics;
    private String metricDefinition;
    private String applicability;
    private String evidence;
    private String unresolvedItemsJson;
    private String failureReason;
    private Long adoptedRevisionId;
    private Long adoptedBy;
    private LocalDateTime adoptedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
