package com.dataocean.module.governance.entity.po;

import java.time.LocalDate;

/** 同一自然日、同一种状态迁移的记录数；只向服务返回聚合，避免加载整段流水。 */
public record IssueTransitionCount(LocalDate eventDay, String previousStatus, String nextStatus, long quantity) {}
