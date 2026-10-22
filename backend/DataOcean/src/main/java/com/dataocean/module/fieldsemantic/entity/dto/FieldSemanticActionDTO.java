package com.dataocean.module.fieldsemantic.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FieldSemanticActionDTO {
    @NotNull
    private Integer expectedVersion;
    private Long revisionId;
    private String action;
    private String reason;
}
