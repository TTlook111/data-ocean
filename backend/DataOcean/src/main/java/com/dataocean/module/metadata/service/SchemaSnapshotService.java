package com.dataocean.module.metadata.service;

import com.dataocean.module.metadata.entity.MetadataSnapshot;

/**
 * 元数据快照服务接口。
 * <p>
 * 管理快照的创建、统计信息更新、查询已发布快照等操作。
 * 快照是元数据采集的产物，记录某一时刻数据源的 Schema 全貌。
 * </p>
 */
public interface SchemaSnapshotService {

    /**
     * 创建新快照（版本号自动递增）。
     *
     * @param datasourceId 数据源ID
     * @param taskId       关联的同步任务ID
     * @return 创建的快照实体
     */
    MetadataSnapshot createSnapshot(Long datasourceId, Long taskId);

    /**
     * 更新快照的统计信息。
     *
     * @param snapshotId  快照ID
     * @param tableCount  表数量
     * @param columnCount 字段数量
     * @param schemaHash  Schema 哈希值
     */
    void updateStats(Long snapshotId, Integer tableCount, Integer columnCount, String schemaHash);

    /**
     * 获取指定数据源当前已发布的快照。
     *
     * @param datasourceId 数据源ID
     * @return 已发布的快照，不存在时返回 null
     */
    MetadataSnapshot getPublishedSnapshot(Long datasourceId);

    /**
     * 根据ID获取快照。
     *
     * @param snapshotId 快照ID
     * @return 快照实体
     */
    MetadataSnapshot getById(Long snapshotId);
}
