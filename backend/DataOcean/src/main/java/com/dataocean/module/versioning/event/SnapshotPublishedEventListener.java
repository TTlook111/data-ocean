package com.dataocean.module.versioning.event;

import com.dataocean.module.governance.service.QualityCheckService;
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
 * 异步记录快照发布和过期事件，并在快照发布后自动触发全量质量检查。
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
    /** 质量检查服务，快照发布后自动触发全量质量检查 */
    private final QualityCheckService qualityCheckService;

    /**
     * 处理快照发布事件。
     * <p>
     * 发布后自动触发全量质量检查（异步执行，不阻塞发布流程）。
     * </p>
     *
     * @param event 快照发布事件
     */
    @Async
    @EventListener
    public void onSnapshotPublished(SnapshotPublishedEvent event) {
        log.info("Snapshot published snapshotId={} datasourceId={} operatorId={}",
                event.getSnapshotId(), event.getDatasourceId(), event.getOperatorId());
        String content = "数据源 " + event.getDatasourceId() + " 的快照 " + event.getSnapshotId()
                + " 已发布为当前版本。";
        sendToRelatedUsers(TYPE_SNAPSHOT_PUBLISHED, "元数据快照已发布", content, event.getOperatorId());

        // 快照发布后自动触发全量质量检查（异步，不阻塞发布流程）
        triggerQualityCheck(event.getSnapshotId());
    }

    /**
     * 处理快照过期事件。
     *
     * @param event 快照过期事件
     */
    @Async
    @EventListener
    public void onSnapshotExpired(SnapshotExpiredEvent event) {
        log.info("Snapshot expired snapshotId={} datasourceId={} replacedBySnapshotId={}",
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

    /**
     * 快照发布后自动触发全量质量检查。
     * <p>
     * 异步执行，失败不影响发布流程。检查完成后会自动联动治理状态
     * （HIGH 级问题将表治理状态设为 RECOMMENDED）。
     * </p>
     *
     * @param snapshotId 快照 ID
     */
    private void triggerQualityCheck(Long snapshotId) {
        try {
            log.info("快照发布后自动触发质量检查 snapshotId={}", snapshotId);
            qualityCheckService.executeQualityCheck(snapshotId, null, null);
            log.info("快照发布后质量检查完成 snapshotId={}", snapshotId);
        } catch (Exception e) {
            // 质量检查失败不影响发布流程，记录错误日志
            log.error("快照发布后质量检查失败 snapshotId={}", snapshotId, e);
        }
    }
}
