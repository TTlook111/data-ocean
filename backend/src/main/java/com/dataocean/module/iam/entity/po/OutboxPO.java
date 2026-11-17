package com.dataocean.module.iam.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 与已提交业务一致的可靠事件及当前派发租约。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class OutboxPO {

    /**
     * 可靠事件的全局标识，消费者据此防重。
     */
    private String eventId;

    /**
     * 约定的业务事件类型，不通过名称猜测处理权限。
     */
    private String eventType;

    /**
     * 事件载荷的协议结构版本。
     */
    private int schemaVersion;

    /**
     * 事件所属业务聚合类型。
     */
    private String aggregateType;

    /**
     * 事件所属业务聚合的稳定标识。
     */
    private String aggregateId;

    /**
     * 产生事件时的业务聚合版本。
     */
    private int aggregateVersion;

    /**
     * 当前主体本次操作的唯一命令编号，用于防重与提交核实。
     */
    private String commandId;

    /**
     * 可靠事件的必要身份与版本载荷，不含秘密请求或业务原文。
     */
    private String payload;

    /**
     * 事件待派发、领取中或已送达状态。
     */
    private String deliveryState;

    /**
     * 可靠事件已领取派发的尝试次数。
     */
    private int attempts;

    /**
     * 事件下次允许领取的 UTC 时刻。
     */
    private LocalDateTime availableAt;

    /**
     * 本次领取事件的随机租约标识，旧租约不能确认新领取。
     */
    private String leaseToken;

    /**
     * 当前事件租约的 UTC 到期时刻。
     */
    private LocalDateTime leasedUntil;

    /**
     * 事件或审计真实发生的 UTC 毫秒时刻。
     */
    private LocalDateTime occurredAt;

    /**
     * 事件在当前有效租约下确认送达的 UTC 时刻。
     */
    private LocalDateTime deliveredAt;

    /**
     * 事件最近一次失败的可披露分类代码，不保存原始异常正文。
     */
    private String lastErrorCode;
}
