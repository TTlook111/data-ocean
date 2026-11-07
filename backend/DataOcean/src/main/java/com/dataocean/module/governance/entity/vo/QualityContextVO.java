package com.dataocean.module.governance.entity.vo;

import lombok.Data;
import java.math.BigDecimal;

/** 治理主任务所需的最小快照定位，不包含元数据目录和连接配置。 */
@Data
public class QualityContextVO {
    private Long id;
    private Long datasourceId;
    private Integer snapshotVersion;
    private String status;
    private BigDecimal qualityScore;
}
