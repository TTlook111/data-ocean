package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RoleVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1UserRoleBindingVO;

import java.util.List;

/**
 * IAM-SIMPLE-1 角色与用户角色绑定的只读查询。
 * <p>
 * 页面首屏显示中文名称、作用和能力摘要；技术码只在排查详情折叠展示。
 * </p>
 */
public interface IamS1RoleQueryService {

    /** 角色列表（含固定功能组合、能力摘要与成员数）。 */
    List<IamS1RoleVO> listRoles(Long operatorUserId, String keyword);

    /** 角色详情。 */
    IamS1RoleVO getRole(Long operatorUserId, Long roleId);

    /** 角色的成员配合与负责源；负责源只表示后台工作范围，不表示可查数据。 */
    List<IamS1UserRoleBindingVO> listMembers(Long operatorUserId, Long roleId);

    /** 某用户的全部角色绑定（含各自负责源）。 */
    List<IamS1UserRoleBindingVO> listUserRoles(Long operatorUserId, Long targetUserId);

    /** 引用数据源信息，供页面显示负责源名称。 */
    List<IamS1DatasourceRefVO> responsibleDatasourcesOfBinding(Long operatorUserId, Long userRoleId);
}
