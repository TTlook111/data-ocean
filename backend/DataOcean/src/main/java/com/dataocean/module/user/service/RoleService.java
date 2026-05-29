package com.dataocean.module.user.service;

import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.vo.UserVO;

import java.util.List;

/**
 * 角色管理业务接口。
 */
public interface RoleService {

    /**
     * 查询所有启用状态的角色列表。
     */
    List<SysRole> listEnabledRoles();

    /**
     * 查询指定角色下的用户成员。
     */
    List<UserVO> listUsersByRole(Long roleId);

    /**
     * 将用户加入指定角色。
     */
    void assignRoleToUser(Long roleId, Long userId);

    /**
     * 将用户从指定角色移除。
     */
    void removeRoleFromUser(Long roleId, Long userId);
}
