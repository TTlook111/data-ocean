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
public final class UpdateDepartmentDTO {
    @NotNull
    @Min(1)
    private Integer expectedVersion;
    @NotBlank
    @Size(max=80)
    private String name;
    @NotBlank
    @Size(max=64)
    private String code;
    private String parentId;
    private String managerUserId;
    @Size(max=300)
    private String note;
    @NotNull
    private Integer sortOrder;
    @NotBlank
    private String reviewToken;
}
