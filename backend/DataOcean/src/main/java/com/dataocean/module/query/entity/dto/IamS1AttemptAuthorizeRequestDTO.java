package com.dataocean.module.query.entity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/** Python AST evidence for one candidate; no row-condition values are accepted. */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class IamS1AttemptAuthorizeRequestDTO {
    @NotBlank
    private String attemptId;
    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    private String sqlHash;
    @NotBlank
    private String sql;
    @NotEmpty
    private List<String> usedTables;
    private List<String> usedColumns;
    @Valid
    @NotEmpty
    private List<IamS1AttemptResourceEvidenceDTO> resources;
}
