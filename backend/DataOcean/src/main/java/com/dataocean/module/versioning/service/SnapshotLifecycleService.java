package com.dataocean.module.versioning.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
import com.dataocean.module.versioning.entity.vo.SnapshotVersionHistoryVO;

/**
 * 快照生命周期服务。
 * <p>
 * 负责快照状态流转、版本历史查询、版本对比和当前发布快照查询。
 * </p>
 */
public interface SnapshotLifecycleService {

    /**
     * 变更快照状态。
     *
     * @param snapshotId    快照 ID
     * @param targetStatus  目标状态
     * @param operatorId    操作人 ID
     * @param reason        变更原因
     */
    void changeStatus(Long snapshotId, String targetStatus, Long operatorId, String reason);

    /**
     * 查询数据源快照版本历史。
     *
     * @param datasourceId 数据源 ID
     * @param page         页码
     * @param size         每页条数
     * @return 快照版本历史分页结果
     */
    Page<SnapshotVersionHistoryVO> listVersionHistory(Long datasourceId, int page, int size);

    /**
     * 对比两个快照版本。
     *
     * @param oldSnapshotId 旧快照 ID
     * @param newSnapshotId 新快照 ID
     * @return 快照差异结果
     */
    SchemaDiffVO compareVersions(Long oldSnapshotId, Long newSnapshotId);

    /**
     * 查询数据源当前已发布快照。
     *
     * @param datasourceId 数据源 ID
     * @return 已发布快照；不存在时返回 null
     */
    MetadataSnapshot getPublishedSnapshot(Long datasourceId);
}
