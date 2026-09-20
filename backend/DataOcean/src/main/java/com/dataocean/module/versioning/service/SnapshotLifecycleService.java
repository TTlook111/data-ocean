package com.dataocean.module.versioning.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
import com.dataocean.module.versioning.entity.vo.SnapshotVersionHistoryVO;

import java.util.Collection;

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
     * 按数据源范围查询版本历史。
     *
     * <p>方法名刻意与单数据源版本区分：`listVersionHistory(Long, int, int)` 与
     * `listVersionHistory(Collection, int, int)` 并存会让 `listVersionHistory(null, 1, 20)`
     * 产生重载歧义、直接编译失败，靠调用方强制转型是掩盖而不是解决。</p>
     *
     * <p>`datasourceIds` 为 null 表示不限制；为**空集合**表示调用者没有任何负责源，
     * 直接返回空页——不能退化成“返回全部数据源”。</p>
     */
    Page<SnapshotVersionHistoryVO> listVersionHistoryInDatasources(Collection<Long> datasourceIds,
                                                                   int page, int size);

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
