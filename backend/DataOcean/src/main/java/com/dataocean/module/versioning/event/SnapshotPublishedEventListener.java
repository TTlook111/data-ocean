package com.dataocean.module.versioning.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 快照生命周期事件监听器。
 * <p>
 * 异步记录快照发布和过期事件，后续可扩展通知、索引刷新等处理。
 * </p>
 */
@Slf4j
@Component
public class SnapshotPublishedEventListener {

    /**
     * 处理快照发布事件。
     *
     * @param event 快照发布事件
     */
    @Async
    @EventListener
    public void onSnapshotPublished(SnapshotPublishedEvent event) {
        log.info("快照已发布 - snapshotId: {}, datasourceId: {}, 操作人: {}",
                event.getSnapshotId(), event.getDatasourceId(), event.getOperatorId());
    }

    /**
     * 处理快照过期事件。
     *
     * @param event 快照过期事件
     */
    @Async
    @EventListener
    public void onSnapshotExpired(SnapshotExpiredEvent event) {
        log.info("快照已过期 - snapshotId: {}, datasourceId: {}, 被替代为: {}",
                event.getSnapshotId(), event.getDatasourceId(), event.getReplacedBySnapshotId());
    }
}
