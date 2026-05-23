package com.dataocean.module.governance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 元数据质量规则实体。
 * <p>
 * 定义各治理维度的内置或自定义校验规则，以及问题严重级别和扣分值。
 * </p>
 */
@Data
@TableName("metadata_quality_rule")
public class MetadataQualityRule {

    public static final String DIM_COMPLETENESS = "COMPLETENESS";
    public static final String DIM_ACCURACY = "ACCURACY";
    public static final String DIM_CONSISTENCY = "CONSISTENCY";
    public static final String DIM_TIMELINESS = "TIMELINESS";
    public static final String DIM_TRACEABILITY = "TRACEABILITY";

    public static final String SEV_HIGH = "HIGH";
    public static final String SEV_MEDIUM = "MEDIUM";
    public static final String SEV_LOW = "LOW";

    public static final String TARGET_TABLE = "TABLE";
    public static final String TARGET_COLUMN = "COLUMN";
    public static final String TARGET_RELATION = "RELATION";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleCode;
    private String ruleName;
    private String dimension;
    private String severity;
    private String description;
    private String checkTarget;
    private Integer enabled;
    private BigDecimal deductionPoints;
    private Integer builtin;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
