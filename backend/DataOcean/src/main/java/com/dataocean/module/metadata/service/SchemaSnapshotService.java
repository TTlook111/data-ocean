package com.dataocean.module.metadata.service;

import com.dataocean.module.metadata.entity.MetadataSnapshot;

public interface SchemaSnapshotService {

    MetadataSnapshot createSnapshot(Long datasourceId, Long taskId);

    void updateStats(Long snapshotId, Integer tableCount, Integer columnCount, String schemaHash);

    MetadataSnapshot getPublishedSnapshot(Long datasourceId);

    MetadataSnapshot getById(Long snapshotId);
}
