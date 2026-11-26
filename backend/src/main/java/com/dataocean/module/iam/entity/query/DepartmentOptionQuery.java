package com.dataocean.module.iam.entity.query;

import java.util.List;

import lombok.Data;

/**
 * 部门与成员的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
public class DepartmentOptionQuery {

    /**
     * 本次选项查询或系统功能的明确业务用途。
     */
    private String purpose = null;

    /**
     * 仅在该选项目的允许范围内匹配的搜索文本。
     */
    private String q = null;

    /**
     * 当前已有选择，用于核权后补回只读保留项。
     */
    private List<String> selectedIds = List.of();
}
