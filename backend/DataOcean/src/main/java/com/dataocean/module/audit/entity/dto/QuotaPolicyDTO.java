package com.dataocean.module.audit.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 配额策略请求 DTO
 */
@Data
public class QuotaPolicyDTO {
    @NotBlank(message = "主体类型不能为空")
    private String subjectType;
    @NotNull(message = "主体ID不能为空")
    private Long subjectId;
    private Integer dailyQueryLimit;
    private BigDecimal monthlyCostLimit;
    private Boolean enabled = true;
}
