package com.dataocean.module.system.service;

import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知接收人解析器。
 */
@Component
@RequiredArgsConstructor
public class NotificationRecipientResolver {

    private final IamS1RoleMapper roleMapper;

    /**
     * 查询所有启用的超级管理员用户 ID。
     */
    public List<Long> adminUserIds() {
        return roleMapper.selectActiveProtectedAdminUserIds().stream()
                .distinct()
                .toList();
    }
}
