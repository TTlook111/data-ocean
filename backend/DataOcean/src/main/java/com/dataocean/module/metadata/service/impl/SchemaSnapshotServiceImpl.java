package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SchemaSnapshotServiceImpl implements SchemaSnapshotService {

    private final MetadataSnapshotMapper snapshotMapper;

    @Transactional
    @Override
    public MetadataSnapshot createSnapshot(Long datasourceId, Long taskId) {
        Integer maxVersion = getMaxVersion(datasourceId);
        int nextVersion = (maxVersion == null) ? 1 : maxVersion + 1;

        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setDatasourceId(datasourceId);
        snapshot.setSnapshotVersion(nextVersion);
        snapshot.setSchemaHash("");
        snapshot.setStatus(MetadataSnapshot.STATUS_DRAFT);
        snapshot.setTableCount(0);
        snapshot.setColumnCount(0);
        snapshot.setSyncTaskId(taskId);
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    @Transactional
    @Override
    public void updateStats(Long snapshotId, Integer tableCount, Integer columnCount, String schemaHash) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        snapshot.setTableCount(tableCount);
        snapshot.setColumnCount(columnCount);
        snapshot.setSchemaHash(schemaHash);
        snapshotMapper.updateById(snapshot);
    }

    @Override
    public MetadataSnapshot getPublishedSnapshot(Long datasourceId) {
        return snapshotMapper.selectOne(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
                        .orderByDesc(MetadataSnapshot::getSnapshotVersion)
                        .last("LIMIT 1")
        );
    }

    @Override
    public MetadataSnapshot getById(Long snapshotId) {
        return snapshotMapper.selectById(snapshotId);
    }

    private Integer getMaxVersion(Long datasourceId) {
        MetadataSnapshot latest = snapshotMapper.selectOne(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                        .orderByDesc(MetadataSnapshot::getSnapshotVersion)
                        .last("LIMIT 1")
        );
        return latest == null ? null : latest.getSnapshotVersion();
    }
}
