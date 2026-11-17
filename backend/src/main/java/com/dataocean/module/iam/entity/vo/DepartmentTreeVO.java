package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentTreeVO {
    private String id;
    private String code;
    private String name;
    private String lifecycle;
    private int version;
    private boolean matched;
    private List<DepartmentTreeVO> children;
}
