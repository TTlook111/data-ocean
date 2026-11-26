package com.dataocean.module.iam.entity.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class MembershipSelectionDTO {

    /**
     * 人员唯一主部门的稳定标识。
     */
    @NotBlank
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String primaryDepartmentId;

    /**
     * 完整兼任部门集合，不含主部门且不得重复。
     */
    @NotNull
    @Size(max = 100)
    private List<@NotBlank String> secondaryDepartmentIds;
}
