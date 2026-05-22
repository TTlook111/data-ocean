package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据库字段元数据实体类。
 * <p>
 * 记录从业务数据源采集到的字段级别元数据信息，包括字段名、类型、注释、
 * 是否可空、是否主键、统计信息（空值率、去重计数、TopN 值）等，
 * 用于元数据治理、可信度评分和 Schema RAG。
 * </p>
 */
@Data
@TableName("db_column_meta")
public class DbColumnMeta {

    /** 治理状态：已发现（采集后的初始状态） */
    public static final String GOVERNANCE_DISCOVERED = "DISCOVERED";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属快照ID */
    private Long snapshotId;

    /** 所属表元数据ID */
    private Long tableMetaId;

    /** 所属数据源ID */
    private Long datasourceId;

    /** 所属表名 */
    private String tableName;

    /** 字段名 */
    private String columnName;

    /** 字段注释 */
    private String columnComment;

    /** 数据类型（含精度，如 VARCHAR(255)、DECIMAL(10,2)） */
    private String dataType;

    /** 是否可空（1=可空，0=不可空） */
    private Integer isNullable;

    /** 字段默认值 */
    private String columnDefault;

    /** 是否主键（1=是，0=否） */
    private Integer isPrimaryKey;

    /** 字段在表中的序号位置 */
    private Integer ordinalPosition;

    /** 空值率（采样统计，0~1） */
    private BigDecimal nullRate;

    /** 去重计数 */
    private Long distinctCount;

    /** 枚举型字段的 TopN 高频值（JSON 格式） */
    private String enumTopValues;

    /** 最小值 */
    private String minValue;

    /** 最大值 */
    private String maxValue;

    /** 治理状态（DISCOVERED / NORMAL / RECOMMENDED / SENSITIVE / DEPRECATED / BLOCKED） */
    private String governanceStatus;

    /** 可信度评分（0~100） */
    private Integer confidenceScore;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
