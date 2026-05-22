package com.dataocean.module.metadata.service;

import com.dataocean.module.metadata.entity.SchemaSyncTask;

/**
 * Schema 同步任务服务接口。
 * <p>
 * 管理同步任务的生命周期，包括创建任务、更新状态/进度、关联快照和查询最新任务。
 * </p>
 */
public interface SchemaSyncTaskService {

    /**
     * 创建同步任务。
     *
     * @param datasourceId 目标数据源ID
     * @param triggerType  触发方式（MANUAL / SCHEDULED）
     * @param triggeredBy  触发人用户ID（定时任务时为 null）
     * @return 创建的任务实体
     */
    SchemaSyncTask createTask(Long datasourceId, String triggerType, Long triggeredBy);

    /**
     * 更新任务状态。
     *
     * @param taskId       任务ID
     * @param status       新状态
     * @param errorMessage 错误信息（成功时为 null）
     */
    void updateStatus(Long taskId, String status, String errorMessage);

    /**
     * 更新任务进度。
     *
     * @param taskId  任务ID
     * @param total   总数（表数量）
     * @param current 当前已完成数量
     */
    void updateProgress(Long taskId, Integer total, Integer current);

    /**
     * 将快照关联到任务。
     *
     * @param taskId     任务ID
     * @param snapshotId 快照ID
     */
    void linkSnapshot(Long taskId, Long snapshotId);

    /**
     * 获取指定数据源的最新同步任务。
     *
     * @param datasourceId 数据源ID
     * @return 最新的同步任务，不存在时返回 null
     */
    SchemaSyncTask getLatestTask(Long datasourceId);
}
