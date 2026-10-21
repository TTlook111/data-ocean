package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 保存规则配置变更的只追加版本记录；历史检查另由批次规则快照引用。 */
@Data
@TableName("metadata_quality_rule_revision")
public class MetadataQualityRuleRevision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private String ruleCode;
    private String templateCode;
    private Integer configVersion;
    private String action;
    private String ruleName;
    private String dimension;
    private String severity;
    private String description;
    private String checkTarget;
    private String checkType;
    private String scopeType;
    private Long datasourceId;
    private String targetTableName;
    private String targetColumnName;
    private String parametersJson;
    private Integer enabled;
    private BigDecimal deductionPoints;
    private Integer builtin;
    private Integer deleted;
    private Long changedBy;
    private LocalDateTime changedAt;
}
