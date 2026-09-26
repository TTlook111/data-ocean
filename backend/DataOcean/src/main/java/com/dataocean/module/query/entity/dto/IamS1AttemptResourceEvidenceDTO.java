package com.dataocean.module.query.entity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class IamS1AttemptResourceEvidenceDTO {
    @NotBlank
    private String tableName;
    @Valid
    private List<IamS1AttemptColumnEvidenceDTO> columns;
}
