package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RuleEnabledDTO {
    @NotNull
    private Boolean enabled;
}
