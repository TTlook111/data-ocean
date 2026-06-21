package com.dataocean.module.versioning.event;

import com.dataocean.module.system.service.NotificationRecipientResolver;
import com.dataocean.module.system.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotPublishedEventListenerTest {

    @Test
    void publishedEventNotifiesAdminsAndOperatorWithoutDuplicates() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationRecipientResolver recipientResolver = mock(NotificationRecipientResolver.class);
        SnapshotPublishedEventListener listener = new SnapshotPublishedEventListener(
                notificationService,
                recipientResolver
        );
        when(recipientResolver.adminUserIds()).thenReturn(List.of(1L, 2L));

        listener.onSnapshotPublished(new SnapshotPublishedEvent(this, 11L, 7L, 1L, 9L));

        verify(notificationService, times(1)).send(
                "SNAPSHOT_PUBLISHED",
                "元数据快照已发布",
                "数据源 7 的快照 11 已发布为当前版本。",
                1L
        );
        verify(notificationService).send(
                "SNAPSHOT_PUBLISHED",
                "元数据快照已发布",
                "数据源 7 的快照 11 已发布为当前版本。",
                2L
        );
    }

    @Test
    void expiredEventNotifiesAdmins() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationRecipientResolver recipientResolver = mock(NotificationRecipientResolver.class);
        SnapshotPublishedEventListener listener = new SnapshotPublishedEventListener(
                notificationService,
                recipientResolver
        );
        when(recipientResolver.adminUserIds()).thenReturn(List.of(1L));

        listener.onSnapshotExpired(new SnapshotExpiredEvent(this, 9L, 7L, 11L));

        verify(notificationService).send(
                "SNAPSHOT_EXPIRED",
                "元数据快照已过期",
                "数据源 7 的快照 9 已被快照 11 替代并自动过期。",
                1L
        );
    }
}
