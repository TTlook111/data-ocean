package com.dataocean.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知接收人解析器。
 */
@Component
@RequiredArgsConstructor
public class NotificationRecipientResolver {

    private static final String ADMIN_ROLE_CODE = "ADMIN";

    private final RoleMapper roleMapper;

    /**
     * 查询所有启用的超级管理员用户 ID。
     */
    public List<Long> adminUserIds() {
        SysRole adminRole = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, ADMIN_ROLE_CODE)
                .eq(SysRole::getStatus, 1)
                .last("LIMIT 1"));
        if (adminRole == null) {
            return List.of();
        }
        return roleMapper.selectUsersByRoleId(adminRole.getId()).stream()
                .filter(user -> user.getStatus() != null && user.getStatus() == SysUser.STATUS_NORMAL)
                .map(SysUser::getId)
                .distinct()
                .toList();
    }
}
