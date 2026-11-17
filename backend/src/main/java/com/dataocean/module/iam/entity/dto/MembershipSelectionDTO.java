package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class MembershipSelectionDTO {
    @NotBlank
    @Pattern(regexp="[1-9][0-9]{0,18}")
    private String primaryDepartmentId;
    @NotNull
    @Size(max=100)
    private List<@NotBlank String> secondaryDepartmentIds;
}
