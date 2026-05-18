package com.dataocean.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DepartmentCreateRequest {
    private Long parentId;
    @NotBlank(message = "部门名称不能为空")
    private String deptName;
    @NotBlank(message = "部门编码不能为空")
    private String deptCode;
    private Integer sortOrder = 0;
}
