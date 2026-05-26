package com.dataocean.module.audit.entity.vo;

import lombok.Data;

/**
 * 审计统计视图对象
 */
@Data
public class AuditStatsVO {
    /** 查询总数 */
    private Long totalQueries;
    /** 成功数 */
    private Long successCount;
    /** 成功率 */
    private Double successRate;
    /** 平均耗时（毫秒） */
    private Double avgExecutionTimeMs;
    /** 慢查询数 */
    private Long slowQueryCount;
    /** 慢查询占比 */
    private Double slowQueryRate;
}
