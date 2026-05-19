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
@TableName("db_column_meta")
public class DbColumnMeta {

    public static final String GOVERNANCE_DISCOVERED = "DISCOVERED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private Long tableMetaId;
    private Long datasourceId;
    private String tableName;
    private String columnName;
    private String columnComment;
    private String dataType;
    private Integer isNullable;
    private String columnDefault;
    private Integer isPrimaryKey;
    private Integer ordinalPosition;
    private BigDecimal nullRate;
    private Long distinctCount;
    private String enumTopValues;
    private String minValue;
    private String maxValue;
    private String governanceStatus;
    private Integer confidenceScore;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
