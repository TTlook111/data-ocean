package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
public final class DepartmentUpdateProposalDTO {

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    @NotBlank
    @CodePointLength(max = 80)
    private String name;

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    @NotBlank
    @CodePointLength(max = 64)
    private String code;

    /**
     * 当前部门的直接上级标识，空值表示根部门。
     */
    private String parentId;

    /**
     * 以稳定人员标识绑定的部门负责人。
     */
    private String managerUserId;

    /**
     * 当前部门或配置对象的可空业务说明。
     */
    @CodePointLength(max = 300)
    private String note;

    /**
     * 部门同级显示顺序，按整数范围校验。
     */
    @NotNull
    private Integer sortOrder;
}
