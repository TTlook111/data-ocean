package com.dataocean.module.audit.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 血缘创建请求体
 * <p>
 * 用于手动创建 ETL/MANUAL 类型的 LINEAGE 关系。
 * 请求体结构与文档 data-lineage-research.md §4.1.1 定义完全一致。
 * </p>
 *
 * @author dataocean
 */
@Data
public class LineageCreateRequest {

    /** 上游表实体 ID（数据从 source 流向 target，COLUMN→COLUMN 的 LINEAGE 会被拒绝） */
    @NotNull(message = "上游实体 ID 不能为空")
    private Long sourceEntityId;

    /** 下游表实体 ID */
    @NotNull(message = "目标实体 ID 不能为空")
    private Long targetEntityId;

    /** 血缘类型：ETL | MANUAL（QUERY 由系统自动生成，不允许通过 API 创建） */
    @NotEmpty(message = "血缘类型不能为空")
    private String lineageType;

    /** 血缘描述 */
    private String description;

    /**
     * 列级映射（可选）
     * <p>
     * 填写后自动创建 DERIVED_FROM 边（COLUMN → COLUMN），
     * 并作为 LINEAGE 边的 relation_metadata.column_mappings 摘要。
     * </p>
     */
    private List<ColumnMappingItem> columnMappings;
}
