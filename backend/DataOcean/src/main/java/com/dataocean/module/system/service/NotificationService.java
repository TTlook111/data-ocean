package com.dataocean.module.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.system.entity.SysNotification;

public interface NotificationService {
    void send(String type, String title, String content, Long targetUserId);
    Page<SysNotification> listMyNotifications(int page, int pageSize);
    void markAsRead(Long id);
    long countUnread();
}
