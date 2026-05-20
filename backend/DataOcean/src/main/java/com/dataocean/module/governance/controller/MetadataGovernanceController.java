package com.dataocean.module.governance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    @GetMapping("/quality-rules")
    public Result<List<MetadataQualityRule>> listRules() {
        return Result.success(qualityRuleService.listAllRules());
    }

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

    @GetMapping("/snapshots/{snapshotId}/quality-issues")
    public Result<Page<QualityIssueVO>> listIssues(
            @PathVariable Long snapshotId,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(qualityIssueService.listIssues(snapshotId, dimension, severity, status, tableName, page, size));
    }

    @PatchMapping("/quality-issues/{issueId}/status")
    public Result<Void> handleIssue(@PathVariable Long issueId,
                                    @Valid @RequestBody IssueHandleRequestDTO request) {
        qualityIssueService.handleIssue(issueId, request.getStatus(), request.getResolutionNote(), UserContext.currentUserId());
        return Result.success();
    }

    @PatchMapping("/quality-issues/batch-status")
    public Result<Map<String, Integer>> batchHandleIssues(@Valid @RequestBody IssueBatchHandleDTO request) {
        int updated = qualityIssueService.batchHandle(request.getIssueIds(), request.getStatus(), UserContext.currentUserId());
        return Result.success(Map.of("updated", updated));
    }

    @PostMapping("/quality-issues/{issueId}/assign")
    public Result<Void> assignIssue(@PathVariable Long issueId,
                                    @Valid @RequestBody IssueAssignDTO request) {
        qualityIssueService.assignIssue(issueId, request.getAssigneeId());
        return Result.success();
    }

    // ========== 治理状态 ==========

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

    @GetMapping("/snapshots/{snapshotId}/review-records")
    public Result<Page<ReviewRecordVO>> listReviewRecords(
            @PathVariable Long snapshotId,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(reviewService.listRecords(snapshotId, tableName, page, size));
    }
}
