package com.dataocean.module.permission.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.entity.AccessApprovalRequest;
import com.dataocean.module.permission.service.AccessApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据访问审批控制器
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/admin/access-approvals")
@RequiredArgsConstructor
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
     * 查询审批请求列表
     */
    @GetMapping
    public Result<Page<AccessApprovalRequest>> listRequests(
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AccessApprovalRequest> result = approvalService.listRequests(datasourceId, status, page, size);
        return Result.success(result);
    }
}
