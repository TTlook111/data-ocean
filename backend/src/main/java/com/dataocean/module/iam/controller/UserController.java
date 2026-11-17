package com.dataocean.module.iam.controller;

import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.dto.CreateUserDTO;
import com.dataocean.module.iam.entity.dto.CreateUserPreviewDTO;
import com.dataocean.module.iam.entity.dto.EnableUserDTO;
import com.dataocean.module.iam.entity.dto.ResetPasswordDTO;
import com.dataocean.module.iam.entity.dto.RoleAssignmentDTO;
import com.dataocean.module.iam.entity.dto.TransferUserDTO;
import com.dataocean.module.iam.entity.dto.UpdateUserDTO;
import com.dataocean.module.iam.entity.dto.UserImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.query.UserQuery;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserDetailVO;
import com.dataocean.module.iam.entity.vo.UserRowVO;
import com.dataocean.module.iam.service.UserService;

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
 * User 的类型化 HTTP 入口，业务核验委托所属服务。
 */
@RestController
@RequestMapping("/api/v1/iam/users")
@RequiredArgsConstructor
public class UserController {

    /**
     * 当前模块的业务服务。
     */
    private final UserService service;

    /**
     * 按应用条件读取人员及总数。
     */
    @GetMapping
    public Result<PageVO<UserRowVO>> list(@Valid @ModelAttribute UserQuery query) {
        return Result.success(service.list(query));
    }

    /**
     * 读取人员当前资料或归档快照。
     */
    @GetMapping("/{id}")
    public Result<UserDetailVO> detail(@PathVariable String id) {
        return Result.success(service.detail(id));
    }

    /**
     * 核对创建人员的非秘密资料。
     */
    @PostMapping("/impact-preview")
    public Result<ImpactPreviewVO> previewCreate(@Valid @RequestBody CreateUserPreviewDTO data) {
        return Result.success(service.previewCreate(data));
    }

    /**
     * 原子创建人员、凭据与初始归属。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<MutationVO> create(
            @Valid @RequestBody CreateUserDTO data, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.create(data, command));
    }

    /**
     * 核对目标人员的具体动作和影响。
     */
    @PostMapping("/{id}/impact-preview")
    public Result<ImpactPreviewVO> preview(@PathVariable String id, @Valid @RequestBody UserImpactPreviewDTO data) {
        return Result.success(service.preview(id, data));
    }

    /**
     * 完整保存人员资料及获准归属变化。
     */
    @PutMapping("/{id}")
    public Result<MutationVO> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateUserDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.update(id, data, command));
    }

    /**
     * 调整主兼任归属而不改变角色。
     */
    @PutMapping("/{id}/memberships")
    public Result<MutationVO> transfer(
            @PathVariable String id,
            @Valid @RequestBody TransferUserDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.transfer(id, data, command));
    }

    /**
     * 直接保存完整个人角色分配。
     */
    @PutMapping("/{id}/roles")
    public Result<MutationVO> assignRoles(
            @PathVariable String id,
            @Valid @RequestBody RoleAssignmentDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.assignRoles(id, data, command));
    }

    /**
     * 停用人员并检查最后恢复途径。
     */
    @PostMapping("/{id}/disable")
    public Result<MutationVO> disable(
            @PathVariable String id,
            @Valid @RequestBody VersionActionDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.disable(id, data, command));
    }

    /**
     * 复核当前归属和角色后恢复人员。
     */
    @PostMapping("/{id}/enable")
    public Result<MutationVO> enable(
            @PathVariable String id,
            @Valid @RequestBody EnableUserDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.enable(id, data, command));
    }

    /**
     * 结束当前关系并保留归档身份快照。
     */
    @PostMapping("/{id}/archive")
    public Result<MutationVO> archive(
            @PathVariable String id,
            @Valid @RequestBody VersionActionDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.archive(id, data, command));
    }

    /**
     * 重置初始凭据并使旧会话失效。
     */
    @PostMapping("/{id}/password-reset")
    public Result<MutationVO> resetPassword(
            @PathVariable String id,
            @Valid @RequestBody ResetPasswordDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.resetPassword(id, data, command));
    }
}
