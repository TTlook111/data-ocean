package com.dataocean.module.iam.entity.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色授予上限在预览与正式提交中共用的完整功能集合和操作原因。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleGrantLimitProposalDTO implements RoleProposalDTO {

    /**
     * 显式可授予功能集合，与本人使用权分别维护。
     */
    @NotNull
    private List<@NotBlank String> grantablePermissionCodes;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
