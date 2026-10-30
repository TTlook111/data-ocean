package com.dataocean.module.permission.s1.entity.vo;

import java.util.List;

/**
 * 用户角色绑定视图。
 * <p>
 * 页面必须清晰区分“能管理哪些数据源”（{@code responsibleDatasources}）与“能查询哪些数据”（数据授权），
 * 二者不是同一件事。
 * </p>
 */
public record IamS1UserRoleBindingVO(Long userRoleId,
                                     Long userId,
                                     Long roleId,
                                     String roleCode,
                                     String roleName,
                                     boolean roleEnabled,
                                     boolean bindingEnabled,
                                     boolean protectedRole,
                                     List<String> functionNames,
                                     String capabilitySummary,
                                     List<IamS1DatasourceRefVO> responsibleDatasources,
                                     String responsibleDatasourceSummary) {
}
