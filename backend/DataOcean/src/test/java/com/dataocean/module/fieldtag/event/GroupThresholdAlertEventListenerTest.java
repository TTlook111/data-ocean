package com.dataocean.module.fieldtag.event;

import com.dataocean.module.fieldtag.service.impl.UserFeedbackServiceImpl.GroupThresholdAlertEvent;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.system.service.NotificationRecipientResolver;
import com.dataocean.module.system.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupThresholdAlertEventListenerTest {

    @Test
    void sendsFieldConfidenceAlertToAdmins() {
        DbColumnMetaMapper columnMetaMapper = mock(DbColumnMetaMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        NotificationRecipientResolver recipientResolver = mock(NotificationRecipientResolver.class);
        GroupThresholdAlertEventListener listener = new GroupThresholdAlertEventListener(
                columnMetaMapper,
                notificationService,
                recipientResolver
        );

        DbColumnMeta column = new DbColumnMeta();
        column.setTableName("orders");
        column.setColumnName("amount");
        when(columnMetaMapper.selectById(10L)).thenReturn(column);
        when(recipientResolver.adminUserIds()).thenReturn(List.of(1L, 2L));

        listener.onGroupThresholdAlert(new GroupThresholdAlertEvent(10L, 3));

        verify(notificationService).send(
                "FIELD_CONFIDENCE_ALERT",
                "字段可信度告警",
                "字段 orders.amount 在 7 天内收到 3 个不同用户的负向反馈，已触发群体阈值降级，请及时复核。",
                1L
        );
        verify(notificationService).send(
                "FIELD_CONFIDENCE_ALERT",
                "字段可信度告警",
                "字段 orders.amount 在 7 天内收到 3 个不同用户的负向反馈，已触发群体阈值降级，请及时复核。",
                2L
        );
    }
}
