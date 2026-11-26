package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 修改部门输入，资料和上下级关系共同进行版本检查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class UpdateDepartmentDTO extends DepartmentProfileDTO {

    /**
     * 核对时的部门版本，防止覆盖他人已提交修改。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 绑定当前版本及完整资料的核对凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
