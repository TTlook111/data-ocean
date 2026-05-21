package com.dataocean.module.versioning.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

@Getter
public class SnapshotPublishedEvent extends ApplicationEvent {

    private final Long snapshotId;
    private final Long datasourceId;
    private final Long operatorId;
    private final Long previousSnapshotId;
    private final LocalDateTime publishedAt;

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
