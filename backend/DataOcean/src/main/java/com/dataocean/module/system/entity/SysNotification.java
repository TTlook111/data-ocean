package com.dataocean.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统通知实体类
 */
@Data
@TableName("sys_notification")
public class SysNotification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String type;
    private String title;
    private String content;
    private Long targetUserId;
    private Boolean isRead;
    private LocalDateTime createdAt;
}
