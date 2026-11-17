package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部门启用的预览与正式提交共用的成员复核结论和操作原因。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentEnableProposalDTO implements DepartmentProposalDTO {

    /**
     * 恢复部门前是否明确完成成员和保留配置复核。
     */
    @NotNull
    private Boolean reviewedMembers;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
