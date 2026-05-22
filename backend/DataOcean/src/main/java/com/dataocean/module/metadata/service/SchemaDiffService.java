package com.dataocean.module.metadata.service;

import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;

/**
 * Schema 差异对比服务接口。
 * <p>
 * 负责对比两个快照之间的 Schema 变更，生成差异报告并记录变更事件。
 * 用于元数据治理中的变更追踪和风险评估。
 * </p>
 */
public interface SchemaDiffService {

    /**
     * 对比两个快照的 Schema 差异。
     *
     * @param oldSnapshotId 旧快照ID（基准）
     * @param newSnapshotId 新快照ID（待对比）
     * @return Schema 差异视图对象
     */
    SchemaDiffVO compareSnapshots(Long oldSnapshotId, Long newSnapshotId);
}
