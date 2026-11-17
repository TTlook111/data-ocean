package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleProtectedPermissionsProposalDTO {
    @NotBlank
    @Size(max=80)
    private String name;
    @Size(max=300)
    private String description;
    @NotNull
    @Size(min=1)
    private List<@NotBlank String> permissionCodes;
    @NotBlank
    @Size(max=500)
    private String reason;
}
