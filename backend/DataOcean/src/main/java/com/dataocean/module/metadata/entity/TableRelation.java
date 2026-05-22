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
 * 表关系实体类。
 * <p>
 * 记录数据源中表与表之间的关联关系，包括外键关系、推断关系和手动标注关系。
 * 用于 Schema RAG 召回时理解表间关联，辅助多表联合查询的 SQL 生成。
 * </p>
 */
@Data
@TableName("table_relation")
public class TableRelation {

    /** 关系类型：外键 */
    public static final String TYPE_FK = "FK";

    /** 关系类型：推断（基于命名规则等自动推断） */
    public static final String TYPE_INFERRED = "INFERRED";

    /** 关系类型：手动标注 */
    public static final String TYPE_MANUAL = "MANUAL";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属快照ID */
    private Long snapshotId;

    /** 所属数据源ID */
    private Long datasourceId;

    /** 源表名（外键所在表） */
    private String sourceTable;

    /** 源字段名（外键字段） */
    private String sourceColumn;

    /** 目标表名（被引用表） */
    private String targetTable;

    /** 目标字段名（被引用字段） */
    private String targetColumn;

    /** 关系类型（FK / INFERRED / MANUAL） */
    private String relationType;

    /** 关系置信度（0~1，外键为1.0） */
    private BigDecimal confidence;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
