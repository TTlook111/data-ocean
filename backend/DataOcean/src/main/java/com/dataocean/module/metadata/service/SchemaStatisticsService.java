package com.dataocean.module.metadata.service;

/**
 * Schema 统计信息采集服务接口。
 * <p>
 * 负责采集字段级别的统计信息，包括行数估算、空值率、去重计数、TopN 高频值等。
 * 统计信息用于辅助元数据治理和可信度评分。
 * </p>
 */
public interface SchemaStatisticsService {

    /**
     * 采集指定数据源和快照下所有表/字段的统计信息。
     *
     * @param datasourceId 数据源ID
     * @param snapshotId   快照ID
     */
    void collectStatistics(Long datasourceId, Long snapshotId);
}
