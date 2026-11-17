package com.dataocean.module.iam.entity.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色与授予的完整非秘密提案，提交时须重新核对版本和实际资格。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleUpdateProposalDTO {

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    @NotBlank
    @CodePointLength(max = 80)
    private String name;

    /**
     * 当前对象面向业务人员的职责说明。
     */
    @CodePointLength(max = 300)
    private String description;

    /**
     * 完整最终功能使用集合，与可授予集合分别核对。
     */
    @NotNull
    @Size(min = 1)
    private List<@NotBlank String> permissionCodes;
}
