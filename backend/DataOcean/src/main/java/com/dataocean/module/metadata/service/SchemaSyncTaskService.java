package com.dataocean.module.metadata.service;

import com.dataocean.module.metadata.entity.SchemaSyncTask;

public interface SchemaSyncTaskService {

    SchemaSyncTask createTask(Long datasourceId, String triggerType, Long triggeredBy);

    void updateStatus(Long taskId, String status, String errorMessage);

    void updateProgress(Long taskId, Integer total, Integer current);

    void linkSnapshot(Long taskId, Long snapshotId);

    SchemaSyncTask getLatestTask(Long datasourceId);
}
