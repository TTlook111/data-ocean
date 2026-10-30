package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 提交及审核后内容不可变的字段解释修订。 */
@Data
@TableName("field_semantic_revision")
public class FieldSemanticRevision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long definitionId;
    private Long bindingId;
    private Integer revisionNo;
    private Long basedOnRevisionId;
    private String status;
    private String businessName;
    private String meaning;
    private String unit;
    private String currency;
    private String enumSemantics;
    private String metricDefinition;
    private String applicability;
    private String evidence;
    private String unresolvedItemsJson;
    private String glossaryRefsJson;
    private String contentHash;
    private String dependencyHash;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime submittedAt;
}
