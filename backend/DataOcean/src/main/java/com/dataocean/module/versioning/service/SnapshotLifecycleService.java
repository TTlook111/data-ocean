package com.dataocean.module.versioning.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
import com.dataocean.module.versioning.entity.vo.SnapshotVersionHistoryVO;

public interface SnapshotLifecycleService {

    void changeStatus(Long snapshotId, String targetStatus, Long operatorId, String reason);

    Page<SnapshotVersionHistoryVO> listVersionHistory(Long datasourceId, int page, int size);

    SchemaDiffVO compareVersions(Long oldSnapshotId, Long newSnapshotId);

    MetadataSnapshot getPublishedSnapshot(Long datasourceId);
}
