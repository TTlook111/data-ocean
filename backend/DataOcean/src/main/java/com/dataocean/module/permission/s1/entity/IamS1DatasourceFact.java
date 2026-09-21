package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** S1 预览所需的数据源安全摘要。 */
@Data
public class IamS1DatasourceFact {
    private Long id;
    private String name;
    private Integer status;
    private Long deleted;
}
