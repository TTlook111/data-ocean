package com.dataocean.module.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.system.entity.SysNotification;
import com.dataocean.module.system.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统通知控制器
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 分页查询当前用户的通知列表
     */
    @GetMapping
    public Result<Page<SysNotification>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(notificationService.listMyNotifications(page, pageSize));
    }

    /**
     * 标记通知为已读
     */
    @PatchMapping("/{id}/read")
    public Result<Void> markAsRead(@PathVariable Long id) {
        notificationService.markAsRead(id);
        return Result.success("已标记为已读", null);
    }

    /**
     * 获取未读通知数量
     */
    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        return Result.success(Map.of("count", notificationService.countUnread()));
    }
}
