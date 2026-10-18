package com.dataocean.module.metadata.entity.vo;

import com.dataocean.module.metadata.entity.po.MetadataEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 已发布快照对应的数据对象目录。
 *
 * <p>{@code syncStatus} 为空表示历史版本没有记录目录同步状态，页面必须提示可能不完整，
 * 不能把“未记录”当成“已完整同步”。</p>
 */
public record PublishedCatalogVO(
        Long datasourceId,
        Long publishedSnapshotId,
        Integer snapshotVersion,
        String syncStatus,
        String syncError,
        LocalDateTime syncedAt,
        int tableCount,
        int columnCount,
        List<MetadataEntity> entities) {
}
