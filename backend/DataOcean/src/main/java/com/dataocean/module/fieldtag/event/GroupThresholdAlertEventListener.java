package com.dataocean.module.fieldtag.event;

import com.dataocean.module.fieldtag.service.impl.UserFeedbackServiceImpl.GroupThresholdAlertEvent;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.system.service.NotificationRecipientResolver;
import com.dataocean.module.system.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 字段负向反馈群体阈值告警监听器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupThresholdAlertEventListener {

    private static final String TYPE_FIELD_CONFIDENCE_ALERT = "FIELD_CONFIDENCE_ALERT";

    private final DbColumnMetaMapper columnMetaMapper;
    private final NotificationService notificationService;
    private final NotificationRecipientResolver recipientResolver;

    @Async
    @EventListener
    public void onGroupThresholdAlert(GroupThresholdAlertEvent event) {
        DbColumnMeta column = columnMetaMapper.selectById(event.columnMetaId());
        String fieldName = column == null
                ? "字段ID " + event.columnMetaId()
                : column.getTableName() + "." + column.getColumnName();
        String title = "字段可信度告警";
        String content = "字段 " + fieldName + " 在 7 天内收到 "
                + event.dislikeUserCount() + " 个不同用户的负向反馈，已触发群体阈值降级，请及时复核。";

        for (Long adminUserId : recipientResolver.adminUserIds()) {
            notificationService.send(TYPE_FIELD_CONFIDENCE_ALERT, title, content, adminUserId);
        }
        log.info("字段可信度告警通知已发送 columnMetaId={} dislikeUserCount={}",
                event.columnMetaId(), event.dislikeUserCount());
    }
}
