package com.dataocean.module.versioning.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SnapshotPublishedEventListener {

    @Async
    @EventListener
    public void onSnapshotPublished(SnapshotPublishedEvent event) {
        log.info("快照已发布 - snapshotId: {}, datasourceId: {}, 操作人: {}",
                event.getSnapshotId(), event.getDatasourceId(), event.getOperatorId());
    }

    @Async
    @EventListener
    public void onSnapshotExpired(SnapshotExpiredEvent event) {
        log.info("快照已过期 - snapshotId: {}, datasourceId: {}, 被替代为: {}",
                event.getSnapshotId(), event.getDatasourceId(), event.getReplacedBySnapshotId());
    }
}
