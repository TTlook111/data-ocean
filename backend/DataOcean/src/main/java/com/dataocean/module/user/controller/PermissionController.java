package com.dataocean.module.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.module.user.entity.SysPermission;
import com.dataocean.module.user.entity.SysRolePermission;
import com.dataocean.module.user.entity.dto.PermissionSaveDTO;
import com.dataocean.module.user.entity.vo.PermissionTreeVO;
import com.dataocean.module.user.mapper.PermissionMapper;
import com.dataocean.module.user.mapper.RolePermissionMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/admin/permissions", "/api/permissions"})
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('role:view', 'role:manage', 'user:manage', 'security:manage')")
    public Result<List<SysPermission>> list() {
        return Result.success(permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                .orderByAsc(SysPermission::getModule)
                .orderByAsc(SysPermission::getId)));
    }

    @GetMapping("/tree")
    @PreAuthorize("hasAnyAuthority('role:view', 'role:manage', 'user:manage', 'security:manage')")
    public Result<List<PermissionTreeVO>> tree() {
        Map<String, List<SysPermission>> grouped = permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                        .orderByAsc(SysPermission::getModule)
                        .orderByAsc(SysPermission::getId))
                .stream()
                .collect(Collectors.groupingBy(SysPermission::getModule, java.util.LinkedHashMap::new, Collectors.toList()));
        return Result.success(grouped.entrySet().stream()
                .map(entry -> PermissionTreeVO.builder()
                        .module(entry.getKey())
                        .moduleName(entry.getKey())
                        .permissions(entry.getValue())
                        .build())
                .toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<Map<String, Long>> create(@Valid @RequestBody PermissionSaveDTO request) {
        ensureCodeAvailable(request.getPermissionCode(), null);
        SysPermission permission = toEntity(new SysPermission(), request);
        permissionMapper.insert(permission);
        return Result.success("权限创建成功", Map.of("id", permission.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PermissionSaveDTO request) {
        SysPermission permission = requirePermission(id);
        if ("*".equals(permission.getPermissionCode())) {
            throw new BusinessException("内置全部权限不能修改");
        }
        ensureCodeAvailable(request.getPermissionCode(), id);
        permissionMapper.updateById(toEntity(permission, request));
        return Result.success("权限更新成功", null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        SysPermission permission = requirePermission(id);
        if ("*".equals(permission.getPermissionCode())) {
            throw new BusinessException("内置全部权限不能删除");
        }
        Long used = rolePermissionMapper.selectCount(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getPermissionId, id));
        if (used != null && used > 0) {
            throw new BusinessException("权限已分配给角色，无法删除");
        }
        permissionMapper.deleteById(id);
        return Result.success("权限删除成功", null);
    }

    private SysPermission toEntity(SysPermission permission, PermissionSaveDTO request) {
        permission.setPermissionCode(request.getPermissionCode());
        permission.setPermissionName(request.getPermissionName());
        permission.setModule(request.getModule());
        permission.setDescription(request.getDescription());
        return permission;
    }

    private SysPermission requirePermission(Long id) {
        SysPermission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException("权限不存在");
        }
        return permission;
    }

    private void ensureCodeAvailable(String code, Long currentId) {
        SysPermission existing = permissionMapper.selectOne(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getPermissionCode, code));
        if (existing != null && !existing.getId().equals(currentId)) {
            throw new BusinessException("权限编码已存在");
        }
    }
}
