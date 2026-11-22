package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.po.OutboxPO;

/**
 * 可靠事件在通道发布前需落盘的本地业务消费边界。
 */
public interface OutboxLocalConsumer {

    /** 按固定类型路由到业务模块。 */
    String eventType();

    /** 返回是否仍持有可派发的租约，失败通过既有队列恢复。 */
    boolean consume(OutboxPO event);
}
