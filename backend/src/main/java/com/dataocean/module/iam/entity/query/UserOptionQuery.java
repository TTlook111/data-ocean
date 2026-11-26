package com.dataocean.module.iam.entity.query;

import lombok.Data;

/**
 * 人员资料的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
public class UserOptionQuery {

    /**
     * 本次选项查询或系统功能的明确业务用途。
     */
    private String purpose = null;

    /**
     * 本次查询或操作涉及的稳定部门标识。
     */
    private String departmentId = null;

    /**
     * 仅在该选项目的允许范围内匹配的搜索文本。
     */
    private String q = null;
}
