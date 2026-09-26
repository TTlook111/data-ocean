package com.dataocean.module.query.entity.dto;

import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class IamS1AttemptColumnEvidenceDTO {
    @NotBlank
    private String columnName;
    @NotEmpty
    private Set<IamS1ColumnUsage> usages;
}
