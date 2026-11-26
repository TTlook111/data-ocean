package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.NotBlank;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 受保护权限调整的预览与正式提交共用完整角色资料和操作原因。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ToString(callSuper = true)
public class RoleProtectedPermissionsProposalDTO extends RoleProfileDTO {

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
