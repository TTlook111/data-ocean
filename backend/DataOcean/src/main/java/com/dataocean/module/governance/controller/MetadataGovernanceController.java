package com.dataocean.module.governance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.pagination.PageRequest;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.governance.entity.dto.*;
import com.dataocean.module.governance.entity.vo.QualityCheckResultVO;
import com.dataocean.module.governance.entity.vo.QualityIssueVO;
import com.dataocean.module.governance.entity.vo.ReviewRecordVO;
import com.dataocean.module.governance.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 元数据治理管理接口。
 * <p>
 * 提供质量校验、质量规则开关、问题处理、治理状态调整和审核记录查询能力。
 * </p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('metadata:manage')")
public class MetadataGovernanceController {

    private final QualityCheckService qualityCheckService;
    private final QualityRuleService qualityRuleService;
    private final QualityIssueService qualityIssueService;
    private final GovernanceStatusService governanceStatusService;
    private final MetadataReviewService reviewService;

    // ========== 质量校验 ==========

    /**
     * 触发指定快照的质量校验。
     *
     * @param snapshotId 快照 ID
     * @param request    可选的校验维度和表范围
     * @return 质量校验结果
     */
    @PostMapping("/snapshots/{snapshotId}/quality-check")
    public Result<QualityCheckResultVO> triggerQualityCheck(
            @PathVariable Long snapshotId,
            @RequestBody(required = false) QualityCheckRequestDTO request) {
        List<String> dimensions = request != null ? request.getDimensions() : null;
        List<String> tableNames = request != null ? request.getTableNames() : null;
        QualityCheckResultVO result = qualityCheckService.executeQualityCheck(snapshotId, dimensions, tableNames);
        return Result.success(result);
    }

    // ========== 质量规则 ==========

    /**
     * 查询全部质量规则。
     *
     * @return 质量规则列表
     */
    @GetMapping("/quality-rules")
    public Result<List<MetadataQualityRule>> listRules() {
        return Result.success(qualityRuleService.listAllRules());
    }

    /**
     * 启用或停用质量规则。
     *
     * @param ruleId 质量规则 ID
     * @param body   请求体，enabled=true 表示启用
     * @return 操作结果
     */
    @PatchMapping("/quality-rules/{ruleId}")
    public Result<Void> updateRuleEnabled(@PathVariable Long ruleId,
                                           @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled != null) {
            qualityRuleService.updateEnabled(ruleId, enabled);
        }
        return Result.success();
    }

    // ========== 问题清单 ==========

    /**
     * 分页查询快照质量问题。
     *
     * @param snapshotId 快照 ID
     * @param dimension  可选质量维度
     * @param severity   可选严重级别
     * @param status     可选处理状态
     * @param tableName  可选表名
     * @param page       页码
     * @param size       每页条数
     * @return 质量问题分页列表
     */
    @GetMapping("/snapshots/{snapshotId}/quality-issues")
    public Result<Page<QualityIssueVO>> listIssues(
            @PathVariable Long snapshotId,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(qualityIssueService.listIssues(snapshotId, dimension, severity, status, tableName,
                (int) PageRequest.page(page), (int) PageRequest.size(size)));
    }

    /**
     * 处理单个质量问题状态。
     *
     * @param issueId 质量问题 ID
     * @param request 状态处理请求
     * @return 操作结果
     */
    @PatchMapping("/quality-issues/{issueId}/status")
    public Result<Void> handleIssue(@PathVariable Long issueId,
                                     @Valid @RequestBody IssueHandleRequestDTO request) {
        qualityIssueService.handleIssue(issueId, request.getStatus(), request.getResolutionNote(), UserContext.currentUserId());
        return Result.success();
    }

    /**
     * 批量处理质量问题状态。
     *
     * @param request 批量处理请求
     * @return 实际更新的问题数量
     */
    @PatchMapping("/quality-issues/batch-status")
    public Result<Map<String, Integer>> batchHandleIssues(@Valid @RequestBody IssueBatchHandleDTO request) {
        int updated = qualityIssueService.batchHandle(request.getIssueIds(), request.getStatus(), UserContext.currentUserId());
        return Result.success(Map.of("updated", updated));
    }

    /**
     * 分派质量问题负责人。
     *
     * @param issueId 质量问题 ID
     * @param request 分派请求
     * @return 操作结果
     */
    @PostMapping("/quality-issues/{issueId}/assign")
    public Result<Void> assignIssue(@PathVariable Long issueId,
                                     @Valid @RequestBody IssueAssignDTO request) {
        qualityIssueService.assignIssue(issueId, request.getAssigneeId());
        return Result.success();
    }

    // ========== 治理状态 ==========

    /**
     * 更新快照中指定表的治理状态。
     *
     * @param snapshotId 快照 ID
     * @param tableName  表名
     * @param request    治理状态更新请求
     * @return 表状态变更结果
     */
    @PatchMapping("/snapshots/{snapshotId}/tables/{tableName}/governance-status")
    public Result<Map<String, String>> updateTableStatus(
            @PathVariable Long snapshotId,
            @PathVariable String tableName,
            @Valid @RequestBody GovernanceStatusUpdateDTO request) {
        Map<String, String> result = governanceStatusService.updateTableStatus(
                snapshotId, tableName, request.getGovernanceStatus(),
                UserContext.currentUserId(), request.getRemark());
        return Result.success(result);
    }

    /**
     * 更新快照中指定字段的治理状态。
     *
     * @param snapshotId 快照 ID
     * @param columnId   字段元数据 ID
     * @param request    治理状态更新请求
     * @return 字段状态变更结果
     */
    @PatchMapping("/snapshots/{snapshotId}/columns/{columnId}/governance-status")
    public Result<Map<String, String>> updateColumnStatus(
            @PathVariable Long snapshotId,
            @PathVariable Long columnId,
            @Valid @RequestBody GovernanceStatusUpdateDTO request) {
        Map<String, String> result = governanceStatusService.updateColumnStatus(
                snapshotId, columnId, request.getGovernanceStatus(),
                UserContext.currentUserId(), request.getRemark());
        return Result.success(result);
    }

    /**
     * 批量更新指定表下字段的治理状态。
     *
     * @param snapshotId 快照 ID
     * @param tableName  表名
     * @param request    批量状态更新请求
     * @return 批量更新统计结果
     */
    @PatchMapping("/snapshots/{snapshotId}/tables/{tableName}/batch-governance-status")
    public Result<Map<String, Object>> batchUpdateColumnStatus(
            @PathVariable Long snapshotId,
            @PathVariable String tableName,
            @Valid @RequestBody BatchGovernanceStatusDTO request) {
        Map<String, Object> result = governanceStatusService.batchUpdateColumnStatus(
                snapshotId, tableName, request.getGovernanceStatus(),
                UserContext.currentUserId(), request.getRemark(), request.getExcludeColumns());
        return Result.success(result);
    }

    // ========== 审核记录 ==========

    /**
     * 查询快照治理审核记录。
     *
     * @param snapshotId 快照 ID
     * @param tableName  可选表名
     * @param page       页码
     * @param size       每页条数
     * @return 审核记录分页列表
     */
    @GetMapping("/snapshots/{snapshotId}/review-records")
    public Result<Page<ReviewRecordVO>> listReviewRecords(
            @PathVariable Long snapshotId,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(reviewService.listRecords(snapshotId, tableName,
                (int) PageRequest.page(page), (int) PageRequest.size(size)));
    }
}
