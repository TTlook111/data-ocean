package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("table_relation")
public class TableRelation {

    public static final String TYPE_FK = "FK";
    public static final String TYPE_INFERRED = "INFERRED";
    public static final String TYPE_MANUAL = "MANUAL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private Long datasourceId;
    private String sourceTable;
    private String sourceColumn;
    private String targetTable;
    private String targetColumn;
    private String relationType;
    private BigDecimal confidence;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
