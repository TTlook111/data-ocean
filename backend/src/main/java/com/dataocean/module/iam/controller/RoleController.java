package com.dataocean.module.iam.controller;

import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.entity.dto.CreateRoleDTO;
import com.dataocean.module.iam.entity.dto.RoleImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
import com.dataocean.module.iam.entity.dto.UpdateGrantLimitDTO;
import com.dataocean.module.iam.entity.dto.UpdateProtectedRoleDTO;
import com.dataocean.module.iam.entity.dto.UpdateRoleDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.query.PersonQuery;
import com.dataocean.module.iam.entity.query.RoleQuery;
import com.dataocean.module.iam.entity.vo.GrantLimitVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.RoleDetailVO;
import com.dataocean.module.iam.entity.vo.RoleRowVO;
import com.dataocean.module.iam.entity.vo.UserRowVO;
import com.dataocean.module.iam.security.IamRequestPermission;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.iam.security.RoleProfilePermission;
import com.dataocean.module.iam.service.RoleService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Role 的类型化 HTTP 入口，业务核验委托所属服务。
 */
@RestController
@RequestMapping("/api/v1/iam/roles")
@RequiredArgsConstructor
public class RoleController {

    /**
     * 当前模块的业务服务。
     */
    private final RoleService service;

    /**
     * 分页读取角色和当前成员数量。
     */
    @GetMapping
    @RequirePermission("roles.view")
    public Result<PageVO<RoleRowVO>> list(@Valid @ModelAttribute RoleQuery query) {
        return Result.success(service.list(query));
    }

    /**
     * 读取角色当前的使用功能。
     */
    @GetMapping("/{id}")
    @RequirePermission("roles.view")
    public Result<RoleDetailVO> detail(@PathVariable String id) {
        return Result.success(service.detail(id));
    }

    /**
     * 分页回看仍持有当前分配的人员。
     */
    @GetMapping("/{id}/members")
    @RequirePermission("roles.view")
    public Result<PageVO<UserRowVO>> members(@PathVariable String id, @Valid @ModelAttribute PersonQuery query) {
        return Result.success(service.members(id, query));
    }

    /**
     * 核对普通角色的新增提案。
     */
    @PostMapping("/impact-preview")
    @RequirePermission("roles.create")
    public Result<ImpactPreviewVO> previewCreate(@Valid @RequestBody CreatePreviewDTO<RoleProfileDTO> data) {
        return Result.success(service.previewCreate(data));
    }

    /**
     * 创建角色及首版历史快照。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("roles.create")
    public Result<MutationVO> create(
            @Valid @RequestBody CreateRoleDTO data, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.create(data, command));
    }

    /**
     * 核对共享角色对全部成员的影响。
     */
    @PostMapping("/{id}/impact-preview")
    @RequirePermission(policy = IamRequestPermission.class)
    public Result<ImpactPreviewVO> preview(@PathVariable String id, @Valid @RequestBody RoleImpactPreviewDTO data) {
        return Result.success(service.preview(id, data));
    }

    /**
     * 单独核权后查看可授予集合。
     */
    @GetMapping("/{id}/grant-limit")
    @RequirePermission("roles.protected")
    public Result<GrantLimitVO> grantLimit(@PathVariable String id) {
        return Result.success(service.grantLimit(id));
    }

    /**
     * 保存普通角色配置，保持受保护项不变。
     */
    @PutMapping("/{id}")
    @RequirePermission(RoleProfilePermission.EDIT)
    public Result<MutationVO> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateRoleDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.update(id, data, command));
    }

    /**
     * 按当前授予资格调整角色上限。
     */
    @PutMapping("/{id}/grant-limit")
    @RequirePermission("roles.protected")
    public Result<MutationVO> updateGrantLimit(
            @PathVariable String id,
            @Valid @RequestBody UpdateGrantLimitDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.updateGrantLimit(id, data, command));
    }

    /**
     * 经受保护核对保存完整功能。
     */
    @PutMapping("/{id}/protected-permissions")
    @RequirePermission("roles.protected")
    public Result<MutationVO> updateProtected(
            @PathVariable String id,
            @Valid @RequestBody UpdateProtectedRoleDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.updateProtected(id, data, command));
    }

    /**
     * 核对角色状态与恢复途径后执行生命周期操作。
     */
    @PostMapping("/{id}/disable")
    @RequirePermission("roles.disable")
    public Result<MutationVO> disable(
            @PathVariable String id,
            @Valid @RequestBody VersionActionDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.lifecycle(id, "DISABLE", data, command));
    }

    /**
     * 核对角色状态与恢复途径后执行生命周期操作。
     */
    @PostMapping("/{id}/enable")
    @RequirePermission("roles.enable")
    public Result<MutationVO> enable(
            @PathVariable String id,
            @Valid @RequestBody VersionActionDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.lifecycle(id, "ENABLE", data, command));
    }

    /**
     * 核对角色状态与恢复途径后执行生命周期操作。
     */
    @PostMapping("/{id}/archive")
    @RequirePermission("roles.archive")
    public Result<MutationVO> archive(
            @PathVariable String id,
            @Valid @RequestBody VersionActionDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.lifecycle(id, "ARCHIVE", data, command));
    }
}
