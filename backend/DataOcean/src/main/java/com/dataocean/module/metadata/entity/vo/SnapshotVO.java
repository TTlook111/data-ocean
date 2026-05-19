package com.dataocean.module.metadata.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SnapshotVO {

    private Long id;
    private Integer snapshotVersion;
    private String datasourceName;
    private Integer tableCount;
    private Integer columnCount;
    private BigDecimal qualityScore;
    private String status;
    private LocalDateTime createdAt;
}
