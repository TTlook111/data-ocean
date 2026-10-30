package com.dataocean.module.audit.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 血缘边响应 VO
 * <p>
 * 用于单条创建和批量创建血缘后的响应。
 * </p>
 *
 * @author dataocean
 */
@Data
public class LineageEdgeVO {

    /** 关系主键 ID */
    private Long relationshipId;

    /** 上游实体 ID */
    private Long sourceEntityId;

    /** 目标实体 ID */
    private Long targetEntityId;

    /** 关系类型（LINEAGE） */
    private String relationType;

    /** 血缘类型（ETL / MANUAL / QUERY） */
    private String lineageType;

    /** 血缘描述 */
    private String description;

    /** 衍生 DERIVED_FROM 边数量 */
    private int derivedCount;

    /** 关系扩展元数据 */
    private Map<String, Object> relationMetadata;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 操作人 */
    private String createdBy;
}
