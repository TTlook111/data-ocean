package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 一次规则配置意图；操作标识和原版本在重试时必须保持不变。 */
@Data
public class QualityRuleOperationDTO {
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private String operationId;

    @NotBlank
    @Pattern(regexp = "CREATE|UPDATE|COPY|ENABLE|DISABLE|DELETE|RESTORE")
    private String action;

    @Positive
    private Long ruleId;

    @Positive
    private Integer expectedVersion;

    @Valid
    private QualityRuleDTO configuration;
}
