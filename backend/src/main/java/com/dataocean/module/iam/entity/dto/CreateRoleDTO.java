package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 新增角色输入，复用普通角色资料和完整功能集合。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class CreateRoleDTO extends RoleProfileDTO {

    /**
     * 绑定本次功能选择的核对凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
