package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("quality_check_batch_rule")
public class QualityCheckBatchRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private Long ruleId;
    private String ruleCode;
    private String templateCode;
    private Integer ruleVersion;
    private String ruleName;
    private String description;
    private String dimension;
    private String severity;
    private java.math.BigDecimal deductionPoints;
    private String scopeType;
    private Long datasourceId;
    private String targetTableName;
    private String targetColumnName;
    private String parametersJson;
    private String status;
    private String reason;
    private Integer targetCount;
    private Integer checkedTargetCount;
    private Integer issueCount;
}
