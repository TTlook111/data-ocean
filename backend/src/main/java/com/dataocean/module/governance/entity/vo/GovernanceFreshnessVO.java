package com.dataocean.module.governance.entity.vo;

import java.time.Instant;

/**
 * 当前配置与实际成功快照的时效。
 *
 * @param scopePending 尚无当前范围的成功采集
 * @param stale 之前目录已经陈旧
 * @param lastApplicableSuccessAt 最近适用成功快照的时间
 * @param staleSince 目录转陈旧的时刻
 */
public record GovernanceFreshnessVO(
        boolean scopePending, boolean stale, Instant lastApplicableSuccessAt, Instant staleSince) {}
