package com.dataocean.module.metadata.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步任务视图对象。
 * <p>
 * 用于前端展示同步任务列表和详情，包含数据源名称、任务状态、进度等信息。
 * </p>
 */
@Data
public class SyncTaskVO {

    /** 任务ID */
    private Long id;

    /** 数据源名称 */
    private String datasourceName;

    /** 触发方式（MANUAL / SCHEDULED） */
    private String triggerType;

    /** 任务状态（PENDING / RUNNING / SUCCESS / FAILED / TIMEOUT） */
    private String status;

    /** 进度总数（表数量） */
    private Integer progressTotal;

    /** 当前已完成数量 */
    private Integer progressCurrent;

    /** 任务开始时间 */
    private LocalDateTime startedAt;

    /** 任务结束时间 */
    private LocalDateTime finishedAt;

    /** 错误信息 */
    private String errorMessage;
}
