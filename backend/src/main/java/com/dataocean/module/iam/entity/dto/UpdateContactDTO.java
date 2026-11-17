package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UpdateContactDTO {
    @NotNull
    @Min(1)
    private Integer expectedVersion;
    @Size(max=32)
    private String phone;
    @Email
    @Size(max=254)
    private String email;
}
