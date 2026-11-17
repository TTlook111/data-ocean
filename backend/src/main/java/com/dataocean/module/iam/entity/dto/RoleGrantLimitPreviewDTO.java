package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleGrantLimitPreviewDTO implements RoleImpactPreviewDTO {
    @NotBlank
    private String action;
    @NotNull
    @Min(1)
    private Integer expectedVersion;
    @NotNull
    @Valid
    private RoleGrantLimitProposalDTO proposal;
}
