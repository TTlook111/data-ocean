package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quality_check_batch")
public class QualityCheckBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private Long snapshotId;
    private String requestedScope;
    private String requestedDimensionsJson;
    private String requestedTableNamesJson;
    private String status;
    private Integer complete;
    private BigDecimal totalScore;
    private Integer ruleCount;
    private Integer executedRuleCount;
    private Integer failedRuleCount;
    private Integer notApplicableRuleCount;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long createdBy;
}
