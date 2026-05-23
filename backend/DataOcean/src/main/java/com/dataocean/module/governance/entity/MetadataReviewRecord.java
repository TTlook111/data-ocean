package com.dataocean.module.governance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据治理审核记录实体。
 * <p>
 * 记录表、字段治理状态调整和质量问题处理等人工审核动作。
 * </p>
 */
@Data
@TableName("metadata_review_record")
public class MetadataReviewRecord {

    public static final String ACTION_STATUS_CHANGE = "STATUS_CHANGE";
    public static final String ACTION_ISSUE_CONFIRM = "ISSUE_CONFIRM";
    public static final String ACTION_ISSUE_RESOLVE = "ISSUE_RESOLVE";
    public static final String ACTION_ISSUE_REJECT = "ISSUE_REJECT";
    public static final String ACTION_BATCH_STATUS_CHANGE = "BATCH_STATUS_CHANGE";
    public static final String ACTION_QUALITY_CHECK_TRIGGER = "QUALITY_CHECK_TRIGGER";

    public static final String TARGET_TABLE = "TABLE";
    public static final String TARGET_COLUMN = "COLUMN";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private Long datasourceId;
    private String targetType;
    private String tableName;
    private String columnName;
    private String action;
    private String oldStatus;
    private String newStatus;
    private Long operatorId;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
