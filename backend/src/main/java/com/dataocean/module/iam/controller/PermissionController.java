package com.dataocean.module.iam.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.vo.PermissionPageVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.iam.service.PermissionService;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 只向已有角色配置入口资格的人员提供系统功能目录。
 */
@RestController
@RequiredArgsConstructor
public class PermissionController {

    /**
     * 当前使用资格与功能目录服务。
     */
    private final PermissionService permissions;

    /**
     * 先核验配置入口，再读取角色可选功能目录。
     */
    @GetMapping("/api/v1/iam/permissions")
    @RequirePermission(anyOf = {"roles.view", "roles.create", "roles.edit", "roles.protected"})
    public Result<List<PermissionPageVO>> catalog() {
        return Result.success(permissions.catalog());
    }
}
