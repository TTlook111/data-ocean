package com.dataocean.module.governance.entity.po;

import java.time.LocalDateTime;

/** 首次一致性读取同时固定快照归属、历史覆盖起点与数据库业务时钟。 */
public record IssueTrendObservation(Long datasourceId, LocalDateTime coverageStart, LocalDateTime asOf) {}
