package com.dataocean.module.governance.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前配置与实际成功快照的时效。
 */
@Data
@AllArgsConstructor
public class GovernanceFreshnessVO {

    /**
     * 尚无当前范围的成功采集。
     */
    private boolean scopePending;

    /**
     * 之前目录已经陈旧。
     */
    private boolean stale;

    /**
     * 最近适用成功快照的时间。
     */
    private Instant lastApplicableSuccessAt;

    /**
     * 目录转陈旧的时刻。
     */
    private Instant staleSince;
}
