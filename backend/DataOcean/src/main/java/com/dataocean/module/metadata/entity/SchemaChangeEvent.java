package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("schema_change_event")
public class SchemaChangeEvent {

    public static final String CHANGE_TABLE_ADDED = "TABLE_ADDED";
    public static final String CHANGE_TABLE_REMOVED = "TABLE_REMOVED";
    public static final String CHANGE_COLUMN_ADDED = "COLUMN_ADDED";
    public static final String CHANGE_COLUMN_REMOVED = "COLUMN_REMOVED";
    public static final String CHANGE_COLUMN_TYPE_CHANGED = "COLUMN_TYPE_CHANGED";
    public static final String CHANGE_COMMENT_CHANGED = "COMMENT_CHANGED";

    public static final String RISK_HIGH = "HIGH";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_LOW = "LOW";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private Long oldSnapshotId;
    private Long newSnapshotId;
    private String changeType;
    private String tableName;
    private String columnName;
    private String oldValue;
    private String newValue;
    private String riskLevel;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
