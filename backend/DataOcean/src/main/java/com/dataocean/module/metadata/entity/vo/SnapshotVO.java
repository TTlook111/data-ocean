package com.dataocean.module.metadata.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 元数据快照视图对象。
 * <p>
 * 用于前端展示快照列表，包含版本号、数据源名称、表/字段数量、质量评分等摘要信息。
 * </p>
 */
@Data
public class SnapshotVO {

    /** 快照ID */
    private Long id;

    /** 快照版本号 */
    private Integer snapshotVersion;

    /** 数据源ID */
    private Long datasourceId;

    /** 数据源名称 */
    private String datasourceName;

    /** 表数量 */
    private Integer tableCount;

    /** 字段数量 */
    private Integer columnCount;

    /** 质量评分（0~100） */
    private BigDecimal qualityScore;

    /** 快照状态 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
