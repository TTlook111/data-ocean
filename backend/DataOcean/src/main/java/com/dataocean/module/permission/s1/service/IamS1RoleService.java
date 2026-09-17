package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.dto.IamS1RoleSaveDTO;

/** IAM-SIMPLE-1 普通角色服务。 */
public interface IamS1RoleService {

    Long createRole(Long operatorUserId, IamS1RoleSaveDTO request);

    void updateRole(Long operatorUserId, Long roleId, IamS1RoleSaveDTO request);

    void deleteRole(Long operatorUserId, Long roleId, String reason);
}
