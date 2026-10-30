package com.dataocean.module.query.entity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class IamS1ModelCallBudgetDTO {
    @NotBlank
    private String callId;
    @NotBlank
    private String nodeName;
    @NotBlank
    private String modelName;
    @Min(0)
    @Max(20000)
    private int inputTokens;
    @Min(0)
    @Max(1024)
    private int outputTokens;
    private boolean usageReported;
}
