package com.dataocean.module.audit.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 列映射条目
 * <p>
 * 对应 LINEAGE 创建请求中的 columnMappings 数组元素。
 * fromColumns 支持多选（参考 OpenLineage inputFields 数组），toColumn 单选。
 * transformationType 仅允许 IDENTITY | TRANSFORMATION | AGGREGATION 三种值。
 * </p>
 *
 * @author dataocean
 */
@Data
public class ColumnMappingItem {

    /** 源列实体 ID 列表（COLUMN 类型，支持多选，对应 OpenLineage inputFields 数组） */
    @NotEmpty(message = "源列 ID 列表不能为空")
    private List<Long> fromColumns;

    /** 目标列实体 ID（COLUMN 类型，单选） */
    @NotNull(message = "目标列 ID 不能为空")
    private Long toColumn;

    /** 转换表达式（原始 SQL 片段，如 "SUM(amount * unit_price)"） */
    private String expression;

    /**
     * 转换类型
     * <p>
     * 前端录入时使用简化版 3 选 1，后端映射到 expression_type：
     * IDENTITY → DIRECT、TRANSFORMATION → ARITHMETIC/CONCAT/CASE_WHEN/CAST、
     * AGGREGATION → AGGREGATION。
     * </p>
     */
    private String transformationType;
}
