package com.dataocean.module.permission.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.permission.entity.AccessApprovalRequest;
import com.dataocean.module.permission.service.AccessApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据访问审批控制器
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/admin/access-approvals")
@RequiredArgsConstructor
@AdminAuditLog
public class AccessApprovalController {

    private final AccessApprovalService approvalService;

    /**
     * 提交数据访问审批请求
     * <p>
     * 用户因 MASK 结果申请查看原始数据时调用。
     * </p>
     */
    @PostMapping
    public Result<Map<String, Long>> submitRequest(@RequestBody AccessApprovalRequest request) {
        request.setRequesterId(UserContext.currentUserId());
        Long id = approvalService.submitRequest(request);
        return Result.success("审批请求已提交", Map.of("id", id));
    }

    /**
     * 审批请求（管理员）
     */
    @PostMapping("/{requestId}/review")
    @PreAuthorize("hasAnyAuthority('security:manage', '*')")
    public Result<Void> reviewRequest(
            @PathVariable Long requestId,
            @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String reason = (String) body.getOrDefault("reason", "");
        approvalService.reviewRequest(requestId, UserContext.currentUserId(), approved, reason);
        return Result.success(approved ? "审批通过" : "审批拒绝", null);
    }

    /**
     * 查询审批请求列表。
     * <p>
     * <b>列表范围由后端强制收窄，不依赖前端。</b>本接口原先没有任何权限限制，
     * 任何登录用户都能列出全部申请——包括他人的申请理由与表字段范围。前端隐藏菜单
     * 不构成安全边界（《开发指导》§3.12）。
     * </p>
     * <p>
     * 具备 `security:manage` 的调用方看到全量审批队列；其余调用方只能看到自己提交的申请。
     * 后者也顺带把「我的申请」的后端能力准备好了——正式开放该入口前仍需产品确认，
     * 但数据隔离已经成立。
     * </p>
     */
    @GetMapping
    public Result<Page<AccessApprovalRequest>> listRequests(
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long requesterId = canReviewAllRequests() ? null : UserContext.currentUserId();
        Page<AccessApprovalRequest> result = approvalService.listRequests(datasourceId, status, requesterId, page, size);
        return Result.success(result);
    }

    /** 当前调用方是否可以查看全部审批请求（审批人视角） */
    private boolean canReviewAllRequests() {
        List<String> permissions = UserContext.currentPermissions();
        return permissions != null && (permissions.contains("*") || permissions.contains("security:manage"));
    }
}
