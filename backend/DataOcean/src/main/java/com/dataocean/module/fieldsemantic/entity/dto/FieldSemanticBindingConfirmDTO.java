package com.dataocean.module.fieldsemantic.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FieldSemanticBindingConfirmDTO {
    @NotNull
    private Integer expectedVersion;
    @NotBlank
    private String evidence;
}
