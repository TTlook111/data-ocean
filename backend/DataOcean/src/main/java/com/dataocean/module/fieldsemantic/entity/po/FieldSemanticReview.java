package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 不可变审核与来源确认凭据。 */
@Data
@TableName("field_semantic_review")
public class FieldSemanticReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long definitionId;
    private Long revisionId;
    private Long bindingId;
    private String action;
    private Integer expectedRowVersion;
    private String contentHash;
    private String dependencyHash;
    private String reason;
    private Long operatorId;
    private LocalDateTime createdAt;
}
