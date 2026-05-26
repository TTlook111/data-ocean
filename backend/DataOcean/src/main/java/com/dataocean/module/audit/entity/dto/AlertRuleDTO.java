package com.dataocean.module.audit.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 告警规则请求 DTO
 */
@Data
public class AlertRuleDTO {
    @NotBlank(message = "监控指标不能为空")
    private String metric;
    @NotNull(message = "阈值不能为空")
    private BigDecimal threshold;
    private String operator = ">";
    private String notificationType = "SYSTEM";
    private Boolean enabled = true;
}
