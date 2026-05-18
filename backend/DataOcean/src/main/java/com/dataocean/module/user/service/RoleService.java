package com.dataocean.module.user.service;

import com.dataocean.module.user.entity.SysRole;

import java.util.List;

public interface RoleService {

    List<SysRole> listEnabledRoles();
}
