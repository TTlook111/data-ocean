package com.dataocean.module.iam.security;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.service.PermissionService;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 角色编辑预览和新命令在授权锁内共用完整提案的授权规则。
 */
@Component
@RequiredArgsConstructor
public class RoleProfilePermission {

    /**
     * 普通编辑在目录、事务命令及审计中的统一动作编码。
     */
    public static final String EDIT = "roles.edit";

    /**
     * 读取本次操作者的使用权、当前授予资格与自我提权约束。
     */
    private final PermissionService permissions;

    /**
     * 从已受锁保护的角色及其权威配置读取实际能力。
     */
    private final RoleMapper roles;

    /**
     * 按当前授权状态核对目标的完整权限内容和操作者的授予上限。
     */
    public void requireChange(RolePO role, List<String> proposed, String operation, boolean protectedFlow) {
        var protectedBefore = role == null
                ? List.<String>of()
                : roles.permissions(role.getId()).stream()
                        .filter(permissions::protectedCode)
                        .sorted()
                        .toList();
        var protectedAfter =
                proposed.stream().filter(permissions::protectedCode).sorted().toList();
        if (!protectedFlow && !protectedBefore.equals(protectedAfter)) {
            // 普通编辑只允许保留已有受保护能力。
            throw new BizIllegalException(ErrorCode.PROTECTED_FLOW_REQUIRED, "受保护能力须单独核验");
        }
        permissions.checkGrant(proposed, operation);
        if (role != null) {
            // 目标已有授予集合属于其完整能力，同时校验操作者不能借自己的角色扩大上限。
            var existingGrants = roles.grantable(role.getId());
            permissions.checkGrant(existingGrants, operation);
            permissions.checkSelfRoleChange(role.getId(), proposed, existingGrants, role.getLifecycle());
        }
    }
}
