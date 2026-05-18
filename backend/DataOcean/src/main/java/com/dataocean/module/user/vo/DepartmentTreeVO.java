package com.dataocean.module.user.vo;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class DepartmentTreeVO {
    private Long id;
    private Long parentId;
    private String deptName;
    private String deptCode;
    private Integer sortOrder;
    @Builder.Default
    private List<DepartmentTreeVO> children = new ArrayList<>();
}
