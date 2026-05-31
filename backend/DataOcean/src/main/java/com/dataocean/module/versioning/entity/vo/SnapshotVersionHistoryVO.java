package com.dataocean.module.versioning.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 快照版本历史视图对象。
 */
@Data
public class SnapshotVersionHistoryVO {

    private Long snapshotId;
    private Long datasourceId;
    private String datasourceName;
    private Integer snapshotVersion;
    private String status;
    private BigDecimal qualityScore;
    private Integer tableCount;
    private Integer columnCount;
    private String schemaHash;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private LocalDateTime expiredAt;
    private String reviewedBy;
}
