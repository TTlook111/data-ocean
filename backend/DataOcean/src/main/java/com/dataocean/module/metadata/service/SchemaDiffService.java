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
     * 对比两个快照的 Schema 差异（只读，不产生任何写库副作用）。
     * <p>
     * 该方法供差异展示类 GET 接口调用，多次调用不会污染变更事件表。
     * </p>
     *
     * @param oldSnapshotId 旧快照ID（基准）
     * @param newSnapshotId 新快照ID（待对比）
     * @return Schema 差异视图对象
     */
    SchemaDiffVO compareSnapshots(Long oldSnapshotId, Long newSnapshotId);

    /**
     * 对比两个快照并将差异持久化为变更事件（幂等）。
     * <p>
     * 显式触发用：写入前会先清除同一快照对（oldSnapshotId, newSnapshotId）的历史事件，
     * 因此重复调用不会产生重复记录。供同步采集或显式 POST 触发调用。
     * </p>
     *
     * @param oldSnapshotId 旧快照ID（基准）
     * @param newSnapshotId 新快照ID（待对比）
     * @return Schema 差异视图对象
     */
    SchemaDiffVO compareAndRecordChanges(Long oldSnapshotId, Long newSnapshotId);
}
