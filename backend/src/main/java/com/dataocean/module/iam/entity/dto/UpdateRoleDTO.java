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
 * 普通角色修改输入，复用完整资料并增加并发及核对约束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class UpdateRoleDTO extends RoleProfileDTO {

    /**
     * 核对时的角色版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 绑定当前角色及完整功能集合的核对凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
