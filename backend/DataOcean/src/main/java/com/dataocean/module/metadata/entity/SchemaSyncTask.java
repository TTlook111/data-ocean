package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Schema 同步任务实体类。
 * <p>
 * 记录每次元数据采集同步任务的执行状态、进度和结果。
 * 支持手动触发和定时触发两种方式，任务状态包括待执行、运行中、成功、失败和超时。
 * </p>
 */
@Data
@TableName("schema_sync_task")
public class SchemaSyncTask {

    /** 任务状态：待执行 */
    public static final String STATUS_PENDING = "PENDING";

    /** 任务状态：运行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 任务状态：成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 任务状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 任务状态：超时 */
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    /** 触发方式：手动 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** 触发方式：定时调度 */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目标数据源ID */
    private Long datasourceId;

    /** 触发方式（MANUAL / SCHEDULED） */
    private String triggerType;

    /** 触发人用户ID（定时任务时为 null） */
    private Long triggeredBy;

    /** 任务状态 */
    private String status;

    /** 进度总数（表数量） */
    private Integer progressTotal;

    /** 当前已完成数量 */
    private Integer progressCurrent;

    /** 关联的快照ID */
    private Long snapshotId;

    /** 错误信息（失败时记录） */
    private String errorMessage;

    /** 任务开始时间 */
    private LocalDateTime startedAt;

    /** 任务结束时间 */
    private LocalDateTime finishedAt;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
