package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class VersionActionDTO {
    @NotNull
    @Min(1)
    private Integer expectedVersion;
    @NotBlank
    private String reviewToken;
    @NotBlank
    @Size(max=500)
    private String reason;
}
