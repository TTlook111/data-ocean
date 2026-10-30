package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Frozen per-field decision and evidence from a semantic review batch. */
@Data
@TableName("semantic_check_result")
public class SemanticCheckResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private Long definitionId;
    private Long bindingId;
    private Long columnMetaId;
    private String tableName;
    private String columnName;
    private String ruleCode;
    private String outcome;
    private Integer ruleVersion;
    private Long revisionId;
    private Long reviewId;
    private String evidenceHash;
    private String reason;
    private Long issueId;
    private LocalDateTime createdAt;
}
