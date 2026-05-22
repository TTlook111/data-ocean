package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Schema 变更事件实体类。
 * <p>
 * 记录两次快照之间检测到的 Schema 变更，包括表新增/删除、字段新增/删除/类型变更、注释变更等。
 * 每条变更事件附带风险等级评估，用于元数据治理审核和变更追踪。
 * </p>
 */
@Data
@TableName("schema_change_event")
public class SchemaChangeEvent {

    /** 变更类型：新增表 */
    public static final String CHANGE_TABLE_ADDED = "TABLE_ADDED";

    /** 变更类型：删除表 */
    public static final String CHANGE_TABLE_REMOVED = "TABLE_REMOVED";

    /** 变更类型：新增字段 */
    public static final String CHANGE_COLUMN_ADDED = "COLUMN_ADDED";

    /** 变更类型：删除字段 */
    public static final String CHANGE_COLUMN_REMOVED = "COLUMN_REMOVED";

    /** 变更类型：字段类型变更 */
    public static final String CHANGE_COLUMN_TYPE_CHANGED = "COLUMN_TYPE_CHANGED";

    /** 变更类型：注释变更 */
    public static final String CHANGE_COMMENT_CHANGED = "COMMENT_CHANGED";

    /** 风险等级：高 */
    public static final String RISK_HIGH = "HIGH";

    /** 风险等级：中 */
    public static final String RISK_MEDIUM = "MEDIUM";

    /** 风险等级：低 */
    public static final String RISK_LOW = "LOW";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属数据源ID */
    private Long datasourceId;

    /** 旧快照ID（变更前） */
    private Long oldSnapshotId;

    /** 新快照ID（变更后） */
    private Long newSnapshotId;

    /** 变更类型 */
    private String changeType;

    /** 涉及的表名 */
    private String tableName;

    /** 涉及的字段名（表级变更时为 null） */
    private String columnName;

    /** 变更前的值 */
    private String oldValue;

    /** 变更后的值 */
    private String newValue;

    /** 风险等级（HIGH / MEDIUM / LOW） */
    private String riskLevel;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
