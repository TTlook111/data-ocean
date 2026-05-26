package com.dataocean.module.fieldtag.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可信度趋势数据点视图对象
 * <p>
 * 表示某个时间点的可信度分数，用于绘制趋势折线图。
 * </p>
 */
@Data
public class ConfidenceTrendPointVO {

    /** 事件时间 */
    private LocalDateTime time;

    /** 分数变化量 */
    private Integer deltaScore;

    /** 事件类型 */
    private String eventType;

    /** 变化后的累计分数（由前端或后端计算） */
    private Integer cumulativeScore;
}
