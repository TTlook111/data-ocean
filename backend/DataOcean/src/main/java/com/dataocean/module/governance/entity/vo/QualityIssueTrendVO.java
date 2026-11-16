package com.dataocean.module.governance.entity.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 一个已授权快照的上海自然日序列，asOf 标识今天数据的实际截止时刻。 */
public record QualityIssueTrendVO(Long datasourceId, Long snapshotId, LocalDateTime asOf,
                                  LocalDateTime coverageStart, List<Day> days) {
    /** 覆盖前保留空值；启用或建快照当天只报告从覆盖起点开始的进出量。 */
    public record Day(LocalDate date, String coverage, Long remaining, Long added,
                      Long reopened, Long resolved, Long otherClosed) {}
}
