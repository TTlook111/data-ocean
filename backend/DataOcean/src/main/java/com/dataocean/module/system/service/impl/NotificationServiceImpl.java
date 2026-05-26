package com.dataocean.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.system.entity.SysNotification;
import com.dataocean.module.system.mapper.SysNotificationMapper;
import com.dataocean.module.system.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 系统通知服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final SysNotificationMapper notificationMapper;

    /**
     * 发送通知
     *
     * @param type         通知类型
     * @param title        通知标题
     * @param content      通知内容
     * @param targetUserId 目标用户ID，为null表示全局通知
     */
    @Override
    public void send(String type, String title, String content, Long targetUserId) {
        SysNotification notification = new SysNotification();
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setTargetUserId(targetUserId);
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        log.debug("发送通知 type={} title={} targetUserId={}", type, title, targetUserId);
    }

    /**
     * 分页查询当前用户的通知列表（包含全局通知）
     */
    @Override
    public Page<SysNotification> listMyNotifications(int page, int pageSize) {
        Long userId = UserContext.currentUserId();
        return notificationMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<SysNotification>()
                        .and(w -> w.eq(SysNotification::getTargetUserId, userId).or().isNull(SysNotification::getTargetUserId))
                        .orderByDesc(SysNotification::getCreatedAt)
        );
    }

    /**
     * 标记通知为已读
     *
     * @param id 通知ID
     */
    @Override
    public void markAsRead(Long id) {
        SysNotification notification = notificationMapper.selectById(id);
        if (notification != null) {
            notification.setIsRead(true);
            notificationMapper.updateById(notification);
        }
    }

    /**
     * 统计当前用户未读通知数量
     */
    @Override
    public long countUnread() {
        Long userId = UserContext.currentUserId();
        return notificationMapper.selectCount(
                new LambdaQueryWrapper<SysNotification>()
                        .and(w -> w.eq(SysNotification::getTargetUserId, userId).or().isNull(SysNotification::getTargetUserId))
                        .eq(SysNotification::getIsRead, false)
        );
    }
}
