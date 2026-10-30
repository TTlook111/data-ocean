package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.dto.IamS1RoleSaveDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RoleVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1UserRoleBindingVO;
import com.dataocean.module.permission.s1.service.IamS1RoleQueryService;
import com.dataocean.module.permission.s1.service.IamS1RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IAM-SIMPLE-1 角色管理。
 * <p>
 * 角色定义只维护名称、说明、状态和固定功能组合；系统管理员角色由服务端固定识别，不能创建、修改或复制。
 * 所有写操作的服务端校验在 {@code IamS1RoleService} 内完成，前端按钮不是安全边界。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1/roles")
@RequiredArgsConstructor
public class IamS1RoleController {

    private final IamS1RoleService roleService;
    private final IamS1RoleQueryService roleQueryService;

    @GetMapping
    public Result<List<IamS1RoleVO>> list(@RequestParam(required = false) String keyword) {
        return Result.success(roleQueryService.listRoles(UserContext.currentUserId(), keyword));
    }

    @GetMapping("/{roleId}")
    public Result<IamS1RoleVO> get(@PathVariable Long roleId) {
        return Result.success(roleQueryService.getRole(UserContext.currentUserId(), roleId));
    }

    /** 角色成员及其负责源；“负责哪些数据源”与“能查询哪些数据”分开显示。 */
    @GetMapping("/{roleId}/members")
    public Result<List<IamS1UserRoleBindingVO>> members(@PathVariable Long roleId) {
        return Result.success(roleQueryService.listMembers(UserContext.currentUserId(), roleId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody IamS1RoleSaveDTO request) {
        return Result.success("S1 角色已创建", roleService.createRole(UserContext.currentUserId(), request));
    }

    @PutMapping("/{roleId}")
    public Result<Void> update(@PathVariable Long roleId, @Valid @RequestBody IamS1RoleSaveDTO request) {
        roleService.updateRole(UserContext.currentUserId(), roleId, request);
        return Result.success("S1 角色已更新", null);
    }

    @DeleteMapping("/{roleId}")
    public Result<Void> delete(@PathVariable Long roleId, @RequestParam(required = false) String reason) {
        roleService.deleteRole(UserContext.currentUserId(), roleId, reason);
        return Result.success("S1 角色已删除", null);
    }
}
