package com.dataocean.module.versioning.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 快照发布事件。
 * <p>
 * 当快照从审核通过状态发布为当前可用版本时发布。
 * </p>
 */
@Getter
public class SnapshotPublishedEvent extends ApplicationEvent {

    private final Long snapshotId;
    private final Long datasourceId;
    private final Long operatorId;
    private final Long previousSnapshotId;
    private final LocalDateTime publishedAt;

    /**
     * 创建快照发布事件。
     *
     * @param source              事件源
     * @param snapshotId          发布快照 ID
     * @param datasourceId        数据源 ID
     * @param operatorId          操作人 ID
     * @param previousSnapshotId  被替代的上一个已发布快照 ID
     */
    public SnapshotPublishedEvent(Object source, Long snapshotId, Long datasourceId,
                                   Long operatorId, Long previousSnapshotId) {
        super(source);
        this.snapshotId = snapshotId;
        this.datasourceId = datasourceId;
        this.operatorId = operatorId;
        this.previousSnapshotId = previousSnapshotId;
        this.publishedAt = LocalDateTime.now();
    }
}
