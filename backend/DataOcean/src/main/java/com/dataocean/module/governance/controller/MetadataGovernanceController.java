package com.dataocean.module.governance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.entity.query.PageQuery;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.governance.entity.dto.*;
import com.dataocean.module.governance.entity.vo.IssueBatchHandleResultVO;
import com.dataocean.module.governance.entity.vo.QualityCheckBatchVO;
import com.dataocean.module.governance.entity.vo.QualityCheckResultVO;
import com.dataocean.module.governance.entity.vo.QualityIssueVO;
import com.dataocean.module.governance.entity.vo.QualityIssueSummaryVO;
import com.dataocean.module.governance.entity.vo.QualityRuleTemplateVO;
import com.dataocean.module.governance.entity.vo.QualityRuleSummaryVO;
import com.dataocean.module.governance.entity.vo.QualityRuleVO;
import com.dataocean.module.governance.entity.vo.QualityRuleRevisionVO;
import com.dataocean.module.governance.entity.vo.ReviewRecordVO;
import com.dataocean.module.governance.entity.query.QualityRuleQuery;
import com.dataocean.module.governance.service.*;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 元数据治理管理接口。
 * <p>
 * 提供质量校验、质量规则开关、问题处理、治理状态调整和审核记录查询能力。
 * 准入使用 IAM-SIMPLE-1 方法级注解：快照类端点用 {@code @IamS1Resource(SNAPSHOT)} 校验真实归属，
 * 问题明细用 {@code @IamS1Resource(GOVERNANCE_ISSUE)}，无单一资源的列表/规则端点用
 * {@code @IamS1ScopedList} 只做功能准入，数据范围由 Service 下推 SQL。
 * </p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@AdminAuditLog
public class MetadataGovernanceController {

    /** 执行质量检查。 */
    private static final String CHECK_FUNCTION = "governance:check";
    /** 查看治理规则：全局规则定义，读取不推导任何数据源访问权。 */
    private static final String RULE_VIEW_FUNCTION = "governance:rule:view";
    /**
     * 维护治理规则：同一功能码覆盖两种范围——全局规则的启停只允许受保护系统管理员，
     * 表/列治理状态则由“功能 + 目标负责源”决定。差异在 Service 内强制。
     */
    private static final String RULE_MANAGE_FUNCTION = "governance:rule:manage";
    /** 查看质量问题。 */
    private static final String ISSUE_VIEW_FUNCTION = "governance:issue:view";
    /** 处理质量问题。 */
    private static final String ISSUE_MANAGE_FUNCTION = "governance:issue:manage";
    /** 快照版本审核记录属于发布域，不因拥有治理查看权而自动获得。 */
    private static final String RELEASE_VIEW_FUNCTION = "metadata:release:view";

    private final QualityCheckService qualityCheckService;
    private final QualityRuleService qualityRuleService;
    private final QualityIssueService qualityIssueService;
    private final GovernanceStatusService governanceStatusService;
    private final MetadataReviewService reviewService;
    private final IamS1CapabilityService capabilityService;
    private final QualityRuleTemplateRegistry templateRegistry;
    private final QualityCheckBatchService batchService;

    /** 调用者在指定功能上负责的数据源 ID；空列表表示没有任何负责源。 */
    private List<Long> visibleDatasourceIds(Long userId, String functionCode) {
        return capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }

    // ========== 质量校验 ==========

    /**
     * 触发指定快照的质量校验。
     *
     * @param snapshotId 快照 ID
     * @param request    可选的校验维度和表范围
     * @return 质量校验结果
     */
    @PostMapping("/snapshots/{snapshotId}/quality-check")
    @IamS1Resource(function = CHECK_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<QualityCheckResultVO> triggerQualityCheck(
            @PathVariable Long snapshotId,
            @RequestBody(required = false) QualityCheckRequestDTO request) {
        List<String> dimensions = request != null ? request.getDimensions() : null;
        List<String> tableNames = request != null ? request.getTableNames() : null;
        QualityCheckResultVO result = qualityCheckService.executeQualityCheck(
                snapshotId, dimensions, tableNames, UserContext.currentUserId());
        return Result.success(result);
    }

    @GetMapping("/snapshots/{snapshotId}/quality-check-batches/latest")
    @IamS1Resource(function = "governance:view", resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<QualityCheckBatchVO> latestQualityCheckBatch(@PathVariable Long snapshotId) {
        return Result.success(batchService.latest(snapshotId));
    }

    @GetMapping("/snapshots/{snapshotId}/quality-rules/summary")
    @IamS1Resource(function = RULE_VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<QualityRuleSummaryVO> snapshotRuleSummary(@PathVariable Long snapshotId) {
        return Result.success(qualityCheckService.ruleSummary(snapshotId));
    }

    @GetMapping("/snapshots/{snapshotId}/quality-issues/summary")
    @IamS1Resource(function = ISSUE_VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<QualityIssueSummaryVO> snapshotIssueSummary(@PathVariable Long snapshotId,
                                                               @RequestParam(defaultValue = "5") int recentLimit) {
        List<Long> visible = visibleDatasourceIds(UserContext.currentUserId(), ISSUE_VIEW_FUNCTION);
        return Result.success(qualityIssueService.snapshotIssueSummary(visible, snapshotId, recentLimit));
    }

    // ========== 质量规则 ==========

    /** 查询当前账号可见的数据源规则与全局规则。 */
    @GetMapping("/quality-rules")
    @IamS1ScopedList(RULE_VIEW_FUNCTION)
    public Result<List<QualityRuleVO>> listRules(@ModelAttribute QualityRuleQuery query) {
        return Result.success(qualityRuleService.listRules(query, UserContext.currentUserId()));
    }

    @GetMapping("/quality-rules/templates")
    @IamS1ScopedList(RULE_VIEW_FUNCTION)
    public Result<List<QualityRuleTemplateVO>> listRuleTemplates() {
        return Result.success(templateRegistry.all().stream().map(definition -> {
            QualityRuleTemplateVO vo = new QualityRuleTemplateVO();
            vo.setCode(definition.getCode());
            vo.setName(definition.getName());
            vo.setDimension(definition.getDimension());
            vo.setTargetType(definition.getTargetType());
            vo.setCheckType(definition.getCheckType());
            vo.setDescription(definition.getDescription());
            vo.setAllowedScopes(List.copyOf(definition.getAllowedScopes()));
            vo.setParameters(definition.getParameters().stream().map(parameter -> {
                QualityRuleTemplateVO.ParameterVO parameterVO = new QualityRuleTemplateVO.ParameterVO();
                parameterVO.setKey(parameter.getKey());
                parameterVO.setLabel(parameter.getLabel());
                parameterVO.setType(parameter.getType());
                parameterVO.setMinimum(parameter.getMinimum());
                parameterVO.setMaximum(parameter.getMaximum());
                parameterVO.setDefaultValue(parameter.getDefaultValue());
                return parameterVO;
            }).toList());
            return vo;
        }).toList());
    }

    @GetMapping("/quality-rules/{ruleId}")
    @IamS1ScopedList(RULE_VIEW_FUNCTION)
    public Result<QualityRuleVO> getRule(@PathVariable Long ruleId,
                                         @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return Result.success(qualityRuleService.getRule(ruleId, UserContext.currentUserId(), includeDeleted));
    }

    @GetMapping("/quality-rules/{ruleId}/history")
    @IamS1ScopedList(RULE_VIEW_FUNCTION)
    public Result<List<com.dataocean.module.governance.entity.vo.QualityCheckBatchRuleVO>> ruleHistory(
            @PathVariable Long ruleId) {
        Long operatorUserId = UserContext.currentUserId();
        qualityRuleService.getRule(ruleId, operatorUserId, true);
        return Result.success(batchService.history(ruleId, operatorUserId));
    }

    @GetMapping("/quality-rules/{ruleId}/revisions")
    @IamS1ScopedList(RULE_VIEW_FUNCTION)
    public Result<List<QualityRuleRevisionVO>> ruleRevisions(@PathVariable Long ruleId) {
        return Result.success(qualityRuleService.history(ruleId, UserContext.currentUserId()));
    }

    @PostMapping("/quality-rules")
    @IamS1ScopedList(RULE_MANAGE_FUNCTION)
    public Result<QualityRuleVO> createRule(@Valid @RequestBody QualityRuleDTO request) {
        return Result.success(qualityRuleService.createRule(request, UserContext.currentUserId()));
    }

    @PutMapping("/quality-rules/{ruleId}")
    @IamS1ScopedList(RULE_MANAGE_FUNCTION)
    public Result<QualityRuleVO> editRule(@PathVariable Long ruleId, @Valid @RequestBody QualityRuleDTO request) {
        return Result.success(qualityRuleService.updateRule(ruleId, request, UserContext.currentUserId()));
    }

    @PostMapping("/quality-rules/{ruleId}/copy")
    @IamS1ScopedList(RULE_MANAGE_FUNCTION)
    public Result<QualityRuleVO> copyRule(@PathVariable Long ruleId) {
        return Result.success(qualityRuleService.copyRule(ruleId, UserContext.currentUserId()));
    }

    @PatchMapping("/quality-rules/{ruleId}/enabled")
    @IamS1ScopedList(RULE_MANAGE_FUNCTION)
    public Result<Void> setRuleEnabled(@PathVariable Long ruleId, @Valid @RequestBody RuleEnabledDTO request) {
        qualityRuleService.updateEnabled(ruleId, Boolean.TRUE.equals(request.getEnabled()), UserContext.currentUserId());
        return Result.success();
    }

    @DeleteMapping("/quality-rules/{ruleId}")
    @IamS1ScopedList(RULE_MANAGE_FUNCTION)
    public Result<Void> deleteRule(@PathVariable Long ruleId) {
        qualityRuleService.deleteRule(ruleId, UserContext.currentUserId());
        return Result.success();
    }

    @PostMapping("/quality-rules/{ruleId}/restore")
    @IamS1ScopedList(RULE_MANAGE_FUNCTION)
    public Result<Void> restoreRule(@PathVariable Long ruleId) {
        qualityRuleService.restoreRule(ruleId, UserContext.currentUserId());
        return Result.success();
    }

    /**
     * 启用或停用质量规则。
     *
     * @param ruleId 质量规则 ID
     * @param body   请求体，enabled=true 表示启用
     * @return 操作结果
     */
    @PatchMapping("/quality-rules/{ruleId}")
    @IamS1ScopedList(RULE_MANAGE_FUNCTION)
    public Result<Void> updateRuleEnabled(@PathVariable Long ruleId,
                                           @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled != null) {
            // 全局规则没有 datasourceId：Service 会要求受保护系统管理员，
            // 非系统管理员即使在某个负责源上有 governance:rule:manage 也不能改全局规则。
            qualityRuleService.updateEnabled(ruleId, enabled, UserContext.currentUserId());
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
     * @param assigneeId 可选责任人用户 ID
     * @param page       页码
     * @param size       每页条数
     * @return 质量问题分页列表
     */
    @GetMapping("/snapshots/{snapshotId}/quality-issues")
    @IamS1Resource(function = ISSUE_VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<Page<QualityIssueVO>> listIssues(
            @PathVariable Long snapshotId,
            @RequestParam(defaultValue = "ALL") String issueDomain,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(qualityIssueService.listIssues(snapshotId, issueDomain, dimension, severity, status, tableName,
                assigneeId, (int) PageQuery.normalizePage(page), (int) PageQuery.normalizePageSize(size)));
    }

    /**
     * 分页查询质量问题；未传快照时返回所有快照的问题。
     *
     * @param snapshotId 可选快照 ID
     * @param dimension  可选质量维度
     * @param severity   可选严重级别
     * @param status     可选处理状态
     * @param tableName  可选表名
     * @param assigneeId 可选责任人用户 ID
     * @param page       页码
     * @param size       每页条数
     * @return 质量问题分页列表
     */
    @GetMapping("/quality-issues")
    @IamS1ScopedList(ISSUE_VIEW_FUNCTION)
    public Result<Page<QualityIssueVO>> listAllIssues(
            @RequestParam(required = false) Long snapshotId,
            @RequestParam(defaultValue = "ALL") String issueDomain,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 未指定快照时按调用者的负责源范围**下推 SQL**；指定快照时同时按快照收窄，
        // 不可见的快照自然查不到，不会变成探测入口。空负责源返回空页而不是全局结果。
        List<Long> visible = visibleDatasourceIds(UserContext.currentUserId(), ISSUE_VIEW_FUNCTION);
        return Result.success(qualityIssueService.listIssuesInDatasources(visible, snapshotId, issueDomain,
                dimension, severity, status, tableName, assigneeId,
                (int) PageQuery.normalizePage(page), (int) PageQuery.normalizePageSize(size)));
    }

    /**
     * 处理单个质量问题状态。
     *
     * @param issueId 质量问题 ID
     * @param request 状态处理请求
     * @return 操作结果
     */
    @PatchMapping("/quality-issues/{issueId}/status")
    @IamS1Resource(function = ISSUE_MANAGE_FUNCTION, resourceType = IamS1ResourceType.GOVERNANCE_ISSUE, resourceIds = "#issueId")
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
    @IamS1ScopedList(ISSUE_MANAGE_FUNCTION)
    public Result<IssueBatchHandleResultVO> batchHandleIssues(@Valid @RequestBody IssueBatchHandleDTO request) {
        return Result.success(qualityIssueService.batchHandle(
                request.getIssueIds(), request.getStatus(), UserContext.currentUserId()));
    }

    /**
     * 分派质量问题负责人。
     *
     * @param issueId 质量问题 ID
     * @param request 分派请求
     * @return 操作结果
     */
    @PostMapping("/quality-issues/{issueId}/assign")
    @IamS1Resource(function = ISSUE_MANAGE_FUNCTION, resourceType = IamS1ResourceType.GOVERNANCE_ISSUE, resourceIds = "#issueId")
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
    @IamS1Resource(function = RULE_MANAGE_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
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
    @IamS1Resource(function = RULE_MANAGE_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
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
    @IamS1Resource(function = RULE_MANAGE_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
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
    @IamS1Resource(function = RELEASE_VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<Page<ReviewRecordVO>> listReviewRecords(
            @PathVariable Long snapshotId,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(reviewService.listRecords(snapshotId, tableName,
                (int) PageQuery.normalizePage(page), (int) PageQuery.normalizePageSize(size)));
    }
}
