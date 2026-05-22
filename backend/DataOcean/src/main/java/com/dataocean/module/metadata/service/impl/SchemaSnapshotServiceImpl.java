package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 元数据快照服务实现类。
 * <p>
 * 负责快照的创建（自动递增版本号）、统计信息更新和查询操作。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SchemaSnapshotServiceImpl implements SchemaSnapshotService {

    private final MetadataSnapshotMapper snapshotMapper;

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public MetadataSnapshot createSnapshot(Long datasourceId, Long taskId) {
        // 获取当前数据源的最大版本号，自动递增
        Integer maxVersion = getMaxVersion(datasourceId);
        int nextVersion = (maxVersion == null) ? 1 : maxVersion + 1;

        // 创建草稿状态的快照
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

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void updateStats(Long snapshotId, Integer tableCount, Integer columnCount, String schemaHash) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        snapshot.setTableCount(tableCount);
        snapshot.setColumnCount(columnCount);
        snapshot.setSchemaHash(schemaHash);
        snapshotMapper.updateById(snapshot);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MetadataSnapshot getPublishedSnapshot(Long datasourceId) {
        // 查询已发布状态的最新版本快照
        return snapshotMapper.selectOne(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
                        .orderByDesc(MetadataSnapshot::getSnapshotVersion)
                        .last("LIMIT 1")
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MetadataSnapshot getById(Long snapshotId) {
        return snapshotMapper.selectById(snapshotId);
    }

    /**
     * 获取指定数据源的最大快照版本号。
     *
     * @param datasourceId 数据源ID
     * @return 最大版本号，无快照时返回 null
     */
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
