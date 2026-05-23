package com.dataocean.module.versioning.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 快照过期事件。
 * <p>
 * 当已发布快照被新版本替代或生命周期结束时发布。
 * </p>
 */
@Getter
public class SnapshotExpiredEvent extends ApplicationEvent {

    private final Long snapshotId;
    private final Long datasourceId;
    private final Long replacedBySnapshotId;
    private final LocalDateTime expiredAt;

    /**
     * 创建快照过期事件。
     *
     * @param source                事件源
     * @param snapshotId            过期快照 ID
     * @param datasourceId          数据源 ID
     * @param replacedBySnapshotId  替代该快照的新快照 ID
     */
    public SnapshotExpiredEvent(Object source, Long snapshotId, Long datasourceId,
                                 Long replacedBySnapshotId) {
        super(source);
        this.snapshotId = snapshotId;
        this.datasourceId = datasourceId;
        this.replacedBySnapshotId = replacedBySnapshotId;
        this.expiredAt = LocalDateTime.now();
    }
}
