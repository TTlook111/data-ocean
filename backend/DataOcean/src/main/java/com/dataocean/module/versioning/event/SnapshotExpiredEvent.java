package com.dataocean.module.versioning.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

@Getter
public class SnapshotExpiredEvent extends ApplicationEvent {

    private final Long snapshotId;
    private final Long datasourceId;
    private final Long replacedBySnapshotId;
    private final LocalDateTime expiredAt;

    public SnapshotExpiredEvent(Object source, Long snapshotId, Long datasourceId,
                                Long replacedBySnapshotId) {
        super(source);
        this.snapshotId = snapshotId;
        this.datasourceId = datasourceId;
        this.replacedBySnapshotId = replacedBySnapshotId;
        this.expiredAt = LocalDateTime.now();
    }
}
