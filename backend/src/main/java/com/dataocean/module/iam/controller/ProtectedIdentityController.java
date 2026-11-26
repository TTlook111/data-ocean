package com.dataocean.module.iam.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.dto.ProtectedRoleAssignmentDTO;
import com.dataocean.module.iam.entity.dto.RecoverUserDTO;
import com.dataocean.module.iam.entity.dto.RecoveryPreviewDTO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.RecoveryPreviewVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.iam.service.RoleService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * ProtectedIdentity 的类型化 HTTP 入口，业务核验委托所属服务。
 */
@RestController
@RequestMapping("/api/v1/iam/users")
@RequiredArgsConstructor
public class ProtectedIdentityController {

    /**
     * 当前模块的业务服务。
     */
    private final RoleService service;

    /**
     * 单独办理个人受保护角色分配。
     */
    @PutMapping("/{id}/protected-roles")
    @RequirePermission({"users.assign", "roles.protected"})
    public Result<MutationVO> assign(
            @PathVariable String id,
            @Valid @RequestBody ProtectedRoleAssignmentDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.protectedAssignment(id, data, command));
    }

    /**
     * 根据指定历史记录构造恢复提案。
     */
    @PostMapping("/{id}/recovery-preview")
    @RequirePermission("users.recover")
    public Result<RecoveryPreviewVO> preview(@PathVariable String id, @Valid @RequestBody RecoveryPreviewDTO data) {
        return Result.success(service.previewRecovery(id, data));
    }

    /**
     * 重核历史和当前资格后建立恢复分配。
     */
    @PostMapping("/{id}/recover")
    @RequirePermission("users.recover")
    public Result<MutationVO> recover(
            @PathVariable String id,
            @Valid @RequestBody RecoverUserDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.recover(id, data, command));
    }
}
