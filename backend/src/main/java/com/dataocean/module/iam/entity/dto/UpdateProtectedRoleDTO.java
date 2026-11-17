package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 受保护角色修改输入，资料复用普通角色结构，操作单独核权。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class UpdateProtectedRoleDTO extends RoleProfileDTO {

    /**
     * 核对时的角色版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 本次受保护调整的必填原因。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;

    /**
     * 绑定受保护配置提案的核对凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
