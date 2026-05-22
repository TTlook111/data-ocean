package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.metadata.entity.SchemaSyncTask;
import com.dataocean.module.metadata.mapper.SchemaSyncTaskMapper;
import com.dataocean.module.metadata.service.SchemaSyncTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Schema 同步任务服务实现类。
 * <p>
 * 管理同步任务的完整生命周期：创建 → 运行 → 成功/失败，
 * 并在状态变更时自动记录开始/结束时间。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SchemaSyncTaskServiceImpl implements SchemaSyncTaskService {

    private final SchemaSyncTaskMapper syncTaskMapper;

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public SchemaSyncTask createTask(Long datasourceId, String triggerType, Long triggeredBy) {
        SchemaSyncTask task = new SchemaSyncTask();
        task.setDatasourceId(datasourceId);
        task.setTriggerType(triggerType);
        task.setTriggeredBy(triggeredBy);
        task.setStatus(SchemaSyncTask.STATUS_PENDING);
        task.setProgressCurrent(0);
        syncTaskMapper.insert(task);
        return task;
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void updateStatus(Long taskId, String status, String errorMessage) {
        SchemaSyncTask task = syncTaskMapper.selectById(taskId);
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        // 进入运行状态时记录开始时间
        if (SchemaSyncTask.STATUS_RUNNING.equals(status)) {
            task.setStartedAt(LocalDateTime.now());
        }
        // 终态（成功或失败）时记录结束时间
        if (SchemaSyncTask.STATUS_SUCCESS.equals(status) || SchemaSyncTask.STATUS_FAILED.equals(status)) {
            task.setFinishedAt(LocalDateTime.now());
        }
        syncTaskMapper.updateById(task);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void updateProgress(Long taskId, Integer total, Integer current) {
        SchemaSyncTask task = syncTaskMapper.selectById(taskId);
        task.setProgressTotal(total);
        task.setProgressCurrent(current);
        syncTaskMapper.updateById(task);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void linkSnapshot(Long taskId, Long snapshotId) {
        SchemaSyncTask task = syncTaskMapper.selectById(taskId);
        task.setSnapshotId(snapshotId);
        syncTaskMapper.updateById(task);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SchemaSyncTask getLatestTask(Long datasourceId) {
        // 按创建时间倒序取第一条
        return syncTaskMapper.selectOne(
                new LambdaQueryWrapper<SchemaSyncTask>()
                        .eq(SchemaSyncTask::getDatasourceId, datasourceId)
                        .orderByDesc(SchemaSyncTask::getCreatedAt)
                        .last("LIMIT 1")
        );
    }
}
