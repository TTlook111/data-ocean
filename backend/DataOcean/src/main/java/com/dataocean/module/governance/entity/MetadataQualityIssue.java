package com.dataocean.module.governance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据质量问题实体。
 * <p>
 * 记录质量校验过程中发现的问题、严重级别、处理状态、负责人和解决信息。
 * </p>
 */
@Data
@TableName("metadata_quality_issue")
public class MetadataQualityIssue {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_AUTO_CLOSED = "AUTO_CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private Long datasourceId;
    private Long ruleId;
    private String dimension;
    private String severity;
    private String tableName;
    private String columnName;
    private String issueDescription;
    private String suggestion;
    private String status;
    private Long assigneeId;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String resolutionNote;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
