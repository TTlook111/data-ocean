package com.dataocean.module.permission.s1.service;

/** IAM-SIMPLE-1 用户角色绑定服务。 */
public interface IamS1UserRoleService {

    Long assignRole(Long operatorUserId, Long targetUserId, Long roleId, String reason);

    void removeRole(Long operatorUserId, Long targetUserId, Long roleId, String reason);

    void disableRoleBinding(Long operatorUserId, Long targetUserId, Long roleId, String reason);
}
