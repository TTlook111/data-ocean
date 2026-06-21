package com.dataocean.module.versioning.event;

import com.dataocean.module.system.service.NotificationRecipientResolver;
import com.dataocean.module.system.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 快照生命周期事件监听器。
 * <p>
 * 异步记录快照发布和过期事件，后续可扩展通知、索引刷新等处理。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotPublishedEventListener {

    private static final String TYPE_SNAPSHOT_PUBLISHED = "SNAPSHOT_PUBLISHED";
    private static final String TYPE_SNAPSHOT_EXPIRED = "SNAPSHOT_EXPIRED";

    private final NotificationService notificationService;
    private final NotificationRecipientResolver recipientResolver;

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
        String content = "数据源 " + event.getDatasourceId() + " 的快照 " + event.getSnapshotId()
                + " 已发布为当前版本。";
        sendToRelatedUsers(TYPE_SNAPSHOT_PUBLISHED, "元数据快照已发布", content, event.getOperatorId());
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
        String content = "数据源 " + event.getDatasourceId() + " 的快照 " + event.getSnapshotId()
                + " 已被快照 " + event.getReplacedBySnapshotId() + " 替代并自动过期。";
        sendToRelatedUsers(TYPE_SNAPSHOT_EXPIRED, "元数据快照已过期", content, null);
    }

    private void sendToRelatedUsers(String type, String title, String content, Long operatorId) {
        Set<Long> userIds = new LinkedHashSet<>(recipientResolver.adminUserIds());
        if (operatorId != null) {
            userIds.add(operatorId);
        }
        for (Long userId : userIds) {
            notificationService.send(type, title, content, userId);
        }
    }
}
