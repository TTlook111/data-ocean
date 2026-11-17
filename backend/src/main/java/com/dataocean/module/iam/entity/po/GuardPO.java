package com.dataocean.module.iam.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记录全局安全版本和首次初始化标记，协调影响授权图的并发变更。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class GuardPO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private Integer id;

    /**
     * 平台当前功能与组织配置的权威安全版本。
     */
    private long securityVersion;

    /**
     * 是否已完成首管理员必要改密并永久关闭初始化例外。
     */
    private boolean bootstrapCompleted;

    /**
     * 该记录最近实际变化的 UTC 时刻。
     */
    private LocalDateTime updatedAt;
}
