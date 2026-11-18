package com.dataocean.module.datasource.entity.vo;

import com.dataocean.common.PageVO;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Scope;

import java.time.Instant;
import java.util.List;

/**
 * 指定来源当前可读取的成功目录。
 *
 * @param page 实际分页及对象清单
 * @param currentSnapshotId 源当前成功快照编号
 * @param catalogVersion 目录投影版本
 * @param effectiveScope 当前配置范围
 * @param snapshotScope 当前成功快照的采集范围
 * @param readableObjectCount 当前范围内可阅读对象数
 */
public record CatalogPageVO(
        PageVO<ObjectRow> page,
        String currentSnapshotId,
        int catalogVersion,
        Scope effectiveScope,
        Scope snapshotScope,
        long readableObjectCount) {

    /**
     * 来源快照中的单个对象摘要。
     *
     * @param objectId 已判定的对象稳定标识
     * @param observationId 本次实际观察
     * @param sourceId 归属数据源
     * @param namespace 来源 Schema
     * @param objectName 源对象名
     * @param qualifiedName 完整限定名
     * @param objectType 对象分类
     * @param sourceComment 来源注释
     * @param catalogStates 对象的一个或多个目录状态
     * @param snapshotId 观察所在快照
     * @param collectedAt 本次成功采集时间
     */
    public record ObjectRow(
            String objectId,
            String observationId,
            String sourceId,
            String namespace,
            String objectName,
            String qualifiedName,
            String objectType,
            String sourceComment,
            List<String> catalogStates,
            String snapshotId,
            Instant collectedAt) {}
}
