package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UpdateUserDTO {
    @NotNull
    @Min(1)
    private Integer expectedVersion;
    @NotBlank
    @Size(max=80)
    private String displayName;
    @NotNull
    @Valid
    private MembershipSelectionDTO membership;
    @Size(max=32)
    private String phone;
    @Email
    @Size(max=254)
    private String email;
    @Size(max=300)
    private String managementNote;
    @NotBlank
    private String reviewToken;
}
