package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建部门请求参数。
 * <p>
 * 管理员新建部门时提交此对象，支持指定上级部门以构建树形组织架构。
 * </p>
 *
 * @author dataocean
 */
@Data
public class DepartmentCreateDTO {

    /** 上级部门ID，顶级部门传 null 或 0 */
    private Long parentId;

    /** 部门名称 */
    @NotBlank(message = "部门名称不能为空")
    private String deptName;

    /** 部门编码，需唯一 */
    @NotBlank(message = "部门编码不能为空")
    private String deptCode;

    /** 排序序号，默认为 0 */
    private Integer sortOrder = 0;
}
