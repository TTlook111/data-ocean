package com.dataocean.module.iam.security;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.iam.entity.dto.DepartmentImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.RoleImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.UserImpactPreviewDTO;
import com.dataocean.module.iam.entity.query.DepartmentOptionQuery;
import com.dataocean.module.iam.entity.query.RoleOptionQuery;
import com.dataocean.module.iam.entity.query.UserOptionQuery;
import com.dataocean.module.iam.service.PermissionService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 将人员、部门、角色的预览动作和选项用途解析为实际权限。
 */
@Component
@RequiredArgsConstructor
public class IamRequestPermission implements RequestPermission {

    /**
     * 查询操作者该次动作的当前使用权限。
     */
    private final PermissionService permissions;

    /**
     * 从各预览或选项接口末尾的业务参数选取权限条件。
     */
    @Override
    public void require(Object[] arguments) {
        Object input = arguments[arguments.length - 1];
        if (input instanceof UserImpactPreviewDTO preview) {
            // 受保护角色分配预览需要双方动作权限。
            requireUserPreview(preview.getAction());
        } else if (input instanceof DepartmentImpactPreviewDTO preview) {
            // 部门预览复用对应提交动作的权限编码。
            permissions.require(departmentOperation(preview.getAction()));
        } else if (input instanceof RoleImpactPreviewDTO preview) {
            // 角色预览按具体动作选择权限，不共享一个宽泛的准入条件。
            requireRolePreview(preview.getAction());
        } else if (input instanceof DepartmentOptionQuery query) {
            // 部门选项使用发起端操作权。
            requireDepartmentOptions(query.getPurpose());
        } else if (input instanceof RoleOptionQuery query) {
            // 角色选项使用原办理动作，在服务中继续按当前授予范围筛选。
            permissions.require(roleOptionOperation(query.getPurpose()));
        } else if (input instanceof UserOptionQuery query) {
            // 人员选项只向已声明用途开放。
            requireUserOptions(query.getPurpose());
        } else {
            // 策略错误绑定到别的参数时拒绝调用。
            throw BizIllegalException.denied();
        }
    }

    /**
     * 在访问人员及版本之前检查预览动作的全部固定权限。
     */
    public void requireUserPreview(String action) {
        switch (action) {
            case "UPDATE" -> permissions.require("users.edit");
            case "TRANSFER" -> permissions.require("users.transfer");
            case "DISABLE" -> permissions.require("users.disable");
            case "ENABLE" -> permissions.require("users.enable");
            case "ARCHIVE" -> permissions.require("users.archive");
            case "RESET_PASSWORD" -> permissions.require("users.password");
            case "PROTECTED_ROLES" -> {
                // 受保护流程既要能分配人员角色，也要能处置受保护能力。
                permissions.require("roles.protected");
                permissions.require("users.assign");
            }
            default -> throw BizIllegalException.invalid("未知人员操作");
        }
    }

    /**
     * 为部门预览和提交使用相同的业务动作编码。
     */
    public static String departmentOperation(String action) {
        return switch (action) {
            case "UPDATE" -> "departments.edit";
            case "ADD_SECONDARY" -> "departments.add-member";
            case "END_MEMBERSHIP" -> "departments.remove-member";
            case "MOVE_MEMBERS", "MOVE_CHILDREN" -> "departments.transfer-members";
            case "DISABLE" -> "departments.disable";
            case "ENABLE" -> "departments.enable";
            case "ARCHIVE" -> "departments.archive";
            default -> throw BizIllegalException.invalid("未知部门操作");
        };
    }

    /**
     * 核对角色预览的操作准入，目标相关条件在读到权威状态后判断。
     */
    public void requireRolePreview(String action) {
        permissions.require(
                switch (action) {
                    case "UPDATE" -> RoleProfilePermission.EDIT;
                    case "GRANT_LIMIT", "PROTECTED_PERMISSIONS" -> "roles.protected";
                    case "DISABLE" -> "roles.disable";
                    case "ENABLE" -> "roles.enable";
                    case "ARCHIVE" -> "roles.archive";
                    default -> throw BizIllegalException.invalid("未知角色操作");
                });
    }

    /**
     * 核对部门选项调用者声明的发起端用途。
     */
    public void requireDepartmentOptions(String purpose) {
        switch (String.valueOf(purpose)) {
            case "USER_CREATE" -> permissions.require("users.create");
            case "USER_TRANSFER" -> permissions.require("users.transfer");
            case "USER_ENABLE" -> permissions.require("users.enable");
            case "DEPARTMENT_EDIT" -> permissions.requireAny("departments.create", "departments.edit");
            case "MEMBER_TRANSFER" -> permissions.require("departments.transfer-members");
            default -> throw BizIllegalException.invalid("部门选项用途不正确");
        }
    }

    /**
     * 解析角色选项的调用动作，供入口和锁内筛选共用。
     */
    public static String roleOptionOperation(String purpose) {
        return switch (String.valueOf(purpose)) {
            case "USER_CREATE" -> "users.create";
            case "USER_ASSIGN" -> "users.assign";
            case "USER_ENABLE" -> "users.enable";
            default -> throw BizIllegalException.invalid("角色选项用途不正确");
        };
    }

    /**
     * 核对人员选项的办理用途。
     */
    public void requireUserOptions(String purpose) {
        switch (String.valueOf(purpose)) {
            case "DEPARTMENT_MANAGER" -> permissions.requireAny("departments.create", "departments.edit");
            case "MEMBER_ADD" -> permissions.require("departments.add-member");
            default -> throw BizIllegalException.invalid("人员选项用途不正确");
        }
    }
}
