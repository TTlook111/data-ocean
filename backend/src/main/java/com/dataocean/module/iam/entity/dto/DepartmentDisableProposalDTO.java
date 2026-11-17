package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.NotBlank;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部门与成员的完整非秘密提案，提交时须重新核对版本和实际资格。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentDisableProposalDTO {

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
