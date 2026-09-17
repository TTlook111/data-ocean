package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** S1 可读取的真实组织路径最小事实。 */
@Data
public class IamS1DepartmentNode {
    private Long id;
    private Long parentId;
    private Integer status;
}
