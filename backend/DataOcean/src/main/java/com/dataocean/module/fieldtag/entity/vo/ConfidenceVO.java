package com.dataocean.module.fieldtag.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可信度视图对象
 * <p>
 * 返回给前端的字段可信度信息。
 * </p>
 */
@Data
public class ConfidenceVO {

    /** 字段元数据ID */
    private Long columnMetaId;

    /** 字段名称（冗余展示） */
    private String columnName;

    /** 表名（冗余展示） */
    private String tableName;

    /** 可信度分数（0-100） */
    private Integer score;

    /** 可信度等级：HIGH/MEDIUM/LOW */
    private String level;

    /** 当前分数的原因说明 */
    private String reason;

    /** 最后更新时间 */
    private LocalDateTime lastUpdated;
}
