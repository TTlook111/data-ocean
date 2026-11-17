package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentRefVO {
    private String id;
    private String code;
    private String name;
    private String lifecycle;
}
