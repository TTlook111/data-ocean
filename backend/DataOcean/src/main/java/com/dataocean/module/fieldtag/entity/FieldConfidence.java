package com.dataocean.module.fieldtag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字段可信度实体类
 * <p>
 * 记录字段的可信度评分（0-100），等级由分数自动计算：
 * HIGH(>=70)、MEDIUM(>=40)、LOW(<40)。
 * 可信度直接影响 RAG 召回排序和 SQL 生成时的字段选择优先级。
 * </p>
 */
@Data
@TableName("field_confidence")
public class FieldConfidence {

    /** 可信度等级：高（分数 >= 70） */
    public static final String LEVEL_HIGH = "HIGH";
    /** 可信度等级：中（分数 >= 40） */
    public static final String LEVEL_MEDIUM = "MEDIUM";
    /** 可信度等级：低（分数 < 40） */
    public static final String LEVEL_LOW = "LOW";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的字段元数据ID */
    private Long columnMetaId;

    /** 可信度分数（0-100） */
    private Integer score;

    /** 可信度等级：HIGH/MEDIUM/LOW */
    private String level;

    /** 当前分数的原因说明 */
    private String reason;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;

    /** 最后更新人用户ID */
    private Long updatedBy;

    /**
     * 根据分数计算可信度等级
     *
     * @param score 可信度分数
     * @return 对应的等级字符串
     */
    public static String calculateLevel(int score) {
        if (score >= 70) {
            return LEVEL_HIGH;
        } else if (score >= 40) {
            return LEVEL_MEDIUM;
        } else {
            return LEVEL_LOW;
        }
    }
}
