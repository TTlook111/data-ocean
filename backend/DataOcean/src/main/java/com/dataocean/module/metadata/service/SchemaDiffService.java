package com.dataocean.module.metadata.service;

import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;

public interface SchemaDiffService {

    SchemaDiffVO compareSnapshots(Long oldSnapshotId, Long newSnapshotId);
}
