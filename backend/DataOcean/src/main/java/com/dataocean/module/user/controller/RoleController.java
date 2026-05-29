package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.dto.RoleUserAssignDTO;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理控制器。
 */
@RestController
@RequestMapping({"/api/admin/roles", "/api/roles"})
@RequiredArgsConstructor
@Slf4j
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('role:view', 'user:manage')")
    public Result<List<SysRole>> listRoles() {
        log.debug("收到角色列表查询请求");
        return Result.success(roleService.listEnabledRoles());
    }

    @GetMapping("/{roleId}/users")
    @PreAuthorize("hasAnyAuthority('role:view', 'role:manage', 'user:manage')")
    public Result<List<UserVO>> listRoleUsers(@PathVariable Long roleId) {
        return Result.success(roleService.listUsersByRole(roleId));
    }

    @PostMapping("/{roleId}/users")
    @PreAuthorize("hasAnyAuthority('role:manage', 'user:manage')")
    public Result<Void> assignRoleToUser(
            @PathVariable Long roleId,
            @Valid @RequestBody RoleUserAssignDTO request) {
        roleService.assignRoleToUser(roleId, request.getUserId());
        return Result.success("成员添加成功", null);
    }

    @DeleteMapping("/{roleId}/users/{userId}")
    @PreAuthorize("hasAnyAuthority('role:manage', 'user:manage')")
    public Result<Void> removeRoleFromUser(@PathVariable Long roleId, @PathVariable Long userId) {
        roleService.removeRoleFromUser(roleId, userId);
        return Result.success("成员移除成功", null);
    }
}
