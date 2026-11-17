package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.ToString;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude={"newPassword","confirmPassword"})
public final class InitialPasswordDTO {
    @NotNull
    @Min(1)
    private Integer expectedVersion;
    @NotBlank
    @Size(min=8,max=32)
    private String newPassword;
    @NotBlank
    private String confirmPassword;
}
