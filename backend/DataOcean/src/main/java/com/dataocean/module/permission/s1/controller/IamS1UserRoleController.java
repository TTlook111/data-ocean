package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.dto.IamS1DatasourceBindingDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1UserRoleAssignDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1UserRoleBindingVO;
import com.dataocean.module.permission.s1.service.IamS1RoleDatasourceService;
import com.dataocean.module.permission.s1.service.IamS1RoleQueryService;
import com.dataocean.module.permission.s1.service.IamS1UserRoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IAM-SIMPLE-1 用户角色绑定与后台负责数据源。
 * <p>
 * 页面只问“选哪个角色、负责哪些数据源”；数据授权不在这里配置。
 * 负责源限制后台工作范围，不授予业务数据查询权。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1")
@RequiredArgsConstructor
public class IamS1UserRoleController {

    private final IamS1UserRoleService userRoleService;
    private final IamS1RoleDatasourceService roleDatasourceService;
    private final IamS1RoleQueryService roleQueryService;

    @GetMapping("/users/{userId}/roles")
    public Result<List<IamS1UserRoleBindingVO>> listUserRoles(@PathVariable Long userId) {
        return Result.success(roleQueryService.listUserRoles(UserContext.currentUserId(), userId));
    }

    @PostMapping("/users/{userId}/roles")
    public Result<Long> assign(@PathVariable Long userId, @Valid @RequestBody IamS1UserRoleAssignDTO request) {
        Long bindingId = userRoleService.assignRole(UserContext.currentUserId(), userId,
                request.getRoleId(), request.getReason());
        return Result.success("角色已分配", bindingId);
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    public Result<Void> remove(@PathVariable Long userId, @PathVariable Long roleId,
                               @RequestParam(required = false) String reason) {
        userRoleService.removeRole(UserContext.currentUserId(), userId, roleId, reason);
        return Result.success("角色已解除", null);
    }

    @PatchMapping("/users/{userId}/roles/{roleId}/disable")
    public Result<Void> disable(@PathVariable Long userId, @PathVariable Long roleId,
                                @RequestParam(required = false) String reason) {
        userRoleService.disableRoleBinding(UserContext.currentUserId(), userId, roleId, reason);
        return Result.success("角色绑定已停用", null);
    }

    @GetMapping("/user-roles/{userRoleId}/datasources")
    public Result<List<IamS1DatasourceRefVO>> datasourcesOfBinding(@PathVariable Long userRoleId) {
        return Result.success(roleQueryService.responsibleDatasourcesOfBinding(
                UserContext.currentUserId(), userRoleId));
    }

    /** 为该用户角色绑定批量设置后台负责数据源（不授予业务查询权）。 */
    @PutMapping("/user-roles/{userRoleId}/datasources")
    public Result<Void> bindDatasources(@PathVariable Long userRoleId,
                                        @Valid @RequestBody IamS1DatasourceBindingDTO request) {
        roleDatasourceService.bindDatasources(UserContext.currentUserId(), userRoleId,
                request.getDatasourceIds(), request.getReason());
        return Result.success("后台负责数据源已保存", null);
    }

    @DeleteMapping("/user-roles/{userRoleId}/datasources/{datasourceId}")
    public Result<Void> removeDatasource(@PathVariable Long userRoleId, @PathVariable Long datasourceId,
                                         @RequestParam(required = false) String reason) {
        roleDatasourceService.removeDatasource(UserContext.currentUserId(), userRoleId, datasourceId, reason);
        return Result.success("后台负责数据源已移除", null);
    }
}
