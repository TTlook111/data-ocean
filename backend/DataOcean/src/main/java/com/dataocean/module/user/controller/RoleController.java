package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理控制器。
 * <p>
 * 提供角色列表查询的 REST API 端点。
 * 同时映射管理端路径 /api/admin/roles 和通用路径 /api/roles。
 * </p>
 *
 * @author DataOcean
 */
@RestController
@RequestMapping({"/api/admin/roles", "/api/roles"})
@RequiredArgsConstructor
@Slf4j
public class RoleController {

    private final RoleService roleService;

    /**
     * 查询启用状态的角色列表。
     * <p>
     * 用于用户管理页面的角色下拉选择等场景。
     * 需要 role:view 或 user:manage 权限。
     * </p>
     *
     * @return 启用状态的角色列表
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('role:view', 'user:manage')")
    public Result<List<SysRole>> listRoles() {
        log.debug("收到角色列表查询请求");
        return Result.success(roleService.listEnabledRoles());
    }
}
