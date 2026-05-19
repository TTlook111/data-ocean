package com.dataocean.module.metadata.service;

public interface SchemaStatisticsService {

    void collectStatistics(Long datasourceId, Long snapshotId);
}
