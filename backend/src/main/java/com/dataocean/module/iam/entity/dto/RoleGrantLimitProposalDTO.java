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
public final class RoleGrantLimitProposalDTO {
    @NotNull
    private List<@NotBlank String> grantablePermissionCodes;
    @NotBlank
    @Size(max=500)
    private String reason;
}
