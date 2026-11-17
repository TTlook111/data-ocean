package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
public final class DepartmentEndMembershipProposalDTO {

    /**
     * 指定的当前成员关系标识，须与路径部门和目标人员匹配。
     */
    @NotBlank
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String membershipId;

    /**
     * 核对时目标人员的当前修订，整批提交时重新检查。
     */
    @NotNull
    @Min(1)
    private Integer userExpectedVersion;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
