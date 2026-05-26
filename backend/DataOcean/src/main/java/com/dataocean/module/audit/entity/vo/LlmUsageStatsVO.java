package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import java.math.BigDecimal;

/**
 * LLM 使用统计视图对象
 */
@Data
public class LlmUsageStatsVO {
    /** 总调用次数 */
    private Long totalCalls;
    /** 总 Token 数 */
    private Long totalTokens;
    /** 总费用（元） */
    private BigDecimal totalCost;
    /** 日均调用次数 */
    private Double avgDailyCalls;
    /** 日均费用 */
    private BigDecimal avgDailyCost;
}
