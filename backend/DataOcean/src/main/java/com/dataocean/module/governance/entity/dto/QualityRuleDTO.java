package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/** 新建或编辑质量规则实例；目标范围由服务端按实际元数据校验。 */
@Data
public class QualityRuleDTO {
    @NotBlank
    @Size(max = 200)
    private String ruleName;

    @NotBlank
    private String templateCode;

    @Size(max = 500)
    private String description;

    @NotBlank
    private String scopeType;

    private Long datasourceId;

    @Size(max = 200)
    private String targetTableName;

    @Size(max = 200)
    private String targetColumnName;

    private Map<String, Object> parameters;

    @NotBlank
    private String severity;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private BigDecimal deductionPoints;

    private Boolean enabled;
}
