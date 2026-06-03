package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.dto.RoleSaveDTO;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/admin/roles", "/api/roles"})
@RequiredArgsConstructor
@Slf4j
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('role:view', 'role:manage', 'user:manage')")
    public Result<List<SysRole>> listRoles() {
        log.debug("list roles");
        return Result.success(roleService.listAllRoles());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<Map<String, Long>> createRole(@Valid @RequestBody RoleSaveDTO request) {
        Long id = roleService.createRole(request);
        return Result.success("角色创建成功", Map.of("id", id));
    }

    @PutMapping("/{roleId}")
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<Void> updateRole(@PathVariable Long roleId, @Valid @RequestBody RoleSaveDTO request) {
        roleService.updateRole(roleId, request);
        return Result.success("角色更新成功", null);
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<Void> deleteRole(@PathVariable Long roleId) {
        roleService.deleteRole(roleId);
        return Result.success("角色删除成功", null);
    }

    @GetMapping("/{roleId}/permissions")
    @PreAuthorize("hasAnyAuthority('role:view', 'role:manage', 'user:manage')")
    public Result<List<Long>> listRolePermissions(@PathVariable Long roleId) {
        return Result.success(roleService.listRolePermissionIds(roleId));
    }

    @PutMapping("/{roleId}/permissions")
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<Void> updateRolePermissions(@PathVariable Long roleId, @RequestBody Map<String, List<Long>> body) {
        roleService.updateRolePermissions(roleId, body.get("permissionIds"));
        return Result.success("权限分配成功", null);
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
