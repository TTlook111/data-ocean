package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserTransferProposalDTO {
    @NotNull
    @Valid
    private MembershipSelectionDTO membership;
    @NotBlank
    @Size(max=500)
    private String reason;
}
