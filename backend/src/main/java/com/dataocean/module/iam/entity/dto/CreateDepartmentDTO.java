package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 新增部门输入，复用完整部门资料。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class CreateDepartmentDTO extends DepartmentProfileDTO {

    /**
     * 绑定新增部门提案的核对凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
