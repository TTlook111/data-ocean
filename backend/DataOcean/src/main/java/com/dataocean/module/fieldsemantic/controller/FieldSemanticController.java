package com.dataocean.module.fieldsemantic.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticActionDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingConfirmDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticDraftDTO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticWorkspaceVO;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import com.dataocean.module.knowledge.service.FieldSemanticUsageService;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 字段解释人工修订、候选和审核接口。 */
@RestController
@RequestMapping("/api/admin/field-semantics/columns/{columnMetaId}")
@RequiredArgsConstructor
@AdminAuditLog
public class FieldSemanticController {
    private static final String VIEW = "governance:field:view";
    private static final String MANAGE = "governance:field:manage";
    /** 审核通过/驳回：与知识文档审核共用独立功能码，不自动带来维护权。 */
    private static final String APPROVE = "knowledge:approve";
    private final FieldSemanticService semanticService;
    private final FieldSemanticUsageService usageService;
    private final com.dataocean.module.fieldsemantic.service.FieldGovernanceProgressService progressService;

    @GetMapping("/governance-progress")
    @IamS1Resource(function = VIEW, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<com.dataocean.module.fieldsemantic.entity.vo.FieldGovernanceProgressVO> progress(
            @PathVariable Long columnMetaId, @RequestParam Long snapshotId) {
        return Result.success(progressService.progress(columnMetaId, snapshotId, UserContext.currentUserId()));
    }

    @GetMapping
    @IamS1Resource(function = VIEW, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> workspace(@PathVariable Long columnMetaId,
                                                       @RequestParam Long snapshotId) {
        return Result.success(semanticService.workspace(columnMetaId, snapshotId, UserContext.currentUserId()));
    }

    @GetMapping("/details")
    @IamS1Resource(function = VIEW, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> details(@PathVariable Long columnMetaId,
                                                     @RequestParam Long snapshotId) {
        return Result.success(semanticService.workspaceDetails(columnMetaId, snapshotId, UserContext.currentUserId()));
    }

    @GetMapping("/usage")
    @IamS1Resource(function = VIEW, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<com.dataocean.module.knowledge.entity.vo.FieldSemanticUsageVO> usage(
            @PathVariable Long columnMetaId, @RequestParam Long snapshotId) {
        return Result.success(usageService.status(columnMetaId, snapshotId, UserContext.currentUserId()));
    }

    @GetMapping("/glossary-options")
    @IamS1Resource(function = VIEW, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<java.util.List<java.util.Map<String, Object>>> glossaryOptions(
            @PathVariable Long columnMetaId, @RequestParam Long snapshotId) {
        return Result.success(semanticService.glossaryOptions(columnMetaId, snapshotId, UserContext.currentUserId()));
    }

    @PutMapping("/draft")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> saveDraft(@PathVariable Long columnMetaId,
                                                       @Valid @RequestBody FieldSemanticDraftDTO request) {
        return Result.success(semanticService.saveDraft(columnMetaId, request, UserContext.currentUserId()));
    }

    @PostMapping("/submit")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> submit(@PathVariable Long columnMetaId,
                                                    @Valid @RequestBody FieldSemanticActionDTO request) {
        return Result.success(semanticService.submit(columnMetaId, request, UserContext.currentUserId()));
    }

    @PostMapping("/withdraw")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> withdraw(@PathVariable Long columnMetaId,
                                                      @Valid @RequestBody FieldSemanticActionDTO request) {
        return Result.success(semanticService.withdraw(columnMetaId, request, UserContext.currentUserId()));
    }

    @PostMapping("/review")
    @IamS1Resource(function = APPROVE, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> review(@PathVariable Long columnMetaId,
                                                    @Valid @RequestBody FieldSemanticActionDTO request) {
        return Result.success(semanticService.review(columnMetaId, request, UserContext.currentUserId()));
    }

    @PostMapping("/revoke")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> revoke(@PathVariable Long columnMetaId,
                                                    @Valid @RequestBody FieldSemanticActionDTO request) {
        return Result.success(semanticService.revoke(columnMetaId, request, UserContext.currentUserId()));
    }

    @PostMapping("/bindings/{snapshotId}/confirm")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> confirmBinding(@PathVariable Long columnMetaId,
                                                            @PathVariable Long snapshotId,
                                                            @Valid @RequestBody FieldSemanticBindingConfirmDTO request) {
        return Result.success(semanticService.confirmBinding(columnMetaId, snapshotId, request,
                UserContext.currentUserId()));
    }

    @PostMapping("/candidates")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> generateCandidate(@PathVariable Long columnMetaId,
                                                               @RequestParam Long snapshotId,
                                                               @RequestParam Integer expectedVersion) {
        return Result.success(semanticService.generateCandidate(columnMetaId, snapshotId, expectedVersion,
                UserContext.currentUserId()));
    }

    @PostMapping("/candidates/{candidateId}/adopt")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<FieldSemanticWorkspaceVO> adoptCandidate(@PathVariable Long columnMetaId,
                                                            @PathVariable Long candidateId,
                                                            @Valid @RequestBody FieldSemanticActionDTO request) {
        return Result.success(semanticService.adoptCandidate(columnMetaId, candidateId, request,
                UserContext.currentUserId()));
    }
}
