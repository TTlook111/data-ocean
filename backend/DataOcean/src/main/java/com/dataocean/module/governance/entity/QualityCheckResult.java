package com.dataocean.module.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量检查结果时序实体。
 * <p>
 * 记录每次质量检查的得分和问题数量，用于质量趋势分析和仪表盘展示。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("quality_check_result")
public class QualityCheckResult {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 快照 ID */
    private Long snapshotId;

    /** 加权总分（维度级结果记录总分） */
    private BigDecimal totalScore;

    /** 规则 ID（维度级结果为空） */
    private Long ruleId;

    /** 校验维度 */
    private String dimension;

    /** 得分 */
    private BigDecimal score;

    /** 问题数量 */
    private Integer issueCount;

    /** 检查时间 */
    private LocalDateTime checkedAt;
}
