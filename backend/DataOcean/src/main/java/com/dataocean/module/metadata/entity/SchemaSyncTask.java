package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("schema_sync_task")
public class SchemaSyncTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private String triggerType;
    private Long triggeredBy;
    private String status;
    private Integer progressTotal;
    private Integer progressCurrent;
    private Long snapshotId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
