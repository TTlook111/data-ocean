package com.dataocean.module.user.service;

import com.dataocean.module.user.entity.SysRole;

import java.util.List;

/**
 * 角色管理业务接口。
 * <p>
 * 提供角色相关的查询功能，当前 MVP 阶段仅支持查询启用状态的角色列表，
 * 用于用户管理页面的角色下拉选择。
 * </p>
 *
 * @author DataOcean
 */
public interface RoleService {

    /**
     * 查询所有启用状态的角色列表。
     * <p>
     * 仅返回 status=1（启用）的角色，按 ID 升序排列。
     * </p>
     *
     * @return 启用状态的角色列表
     */
    List<SysRole> listEnabledRoles();
}
