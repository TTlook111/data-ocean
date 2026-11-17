package com.dataocean.module.iam.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部门与成员的严格影响核对输入，预览本身不写入业务事实。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class CreateDepartmentPreviewDTO {

    /**
     * 与具体动作严格对应的完整结构化提案。
     */
    @NotNull
    @Valid
    private DepartmentProfileDTO proposal;
}
