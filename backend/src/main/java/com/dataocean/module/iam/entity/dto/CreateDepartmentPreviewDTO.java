package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class CreateDepartmentPreviewDTO {
    @NotNull
    @Valid
    private DepartmentProfileDTO proposal;
}
