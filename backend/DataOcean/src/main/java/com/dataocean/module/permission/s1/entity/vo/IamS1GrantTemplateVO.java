package com.dataocean.module.permission.s1.entity.vo;

/**
 * 常见数据授权的预置选项，全部使用中文说明，避免管理员理解 scope/effect/policy 等技术词。
 *
 * @param code             模板码（仅开发与测试使用）
 * @param name             中文名称
 * @param description      勾选后增加的实际能力
 * @param subjectType      默认主体类型：USER/ROLE/DEPARTMENT
 * @param departmentScope  部门继承范围：SELF/INCLUDE_DESCENDANTS，非部门主体为 null
 * @param validDays        默认有效天数，null 表示长期有效
 */
public record IamS1GrantTemplateVO(String code,
                                   String name,
                                   String description,
                                   String subjectType,
                                   String departmentScope,
                                   Integer validDays) {
}
