package com.dataocean.module.audit.entity.vo;

import lombok.Data;

/**
 * 配额检查结果视图对象
 */
@Data
public class QuotaCheckVO {
    /** 是否允许查询 */
    private Boolean allowed;
    /** 今日已用次数 */
    private Integer usedToday;
    /** 每日上限 */
    private Integer dailyLimit;
    /** 剩余次数 */
    private Integer remaining;
    /** 拒绝原因（如超出配额） */
    private String reason;
}
