package com.dataocean.module.fieldsemantic.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingBatchConfirmDTO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticBindingBatchResultVO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticPendingBindingVO;
import com.dataocean.module.fieldsemantic.service.FieldSemanticBindingBatchService;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 同一快照下待确认来源绑定的批量核验。 */
@RestController
@RequestMapping("/api/admin/field-semantics/snapshots/{snapshotId}/bindings")
@RequiredArgsConstructor
@AdminAuditLog
public class FieldSemanticBindingBatchController {
    private static final String MANAGE = "governance:field:manage";
    private final FieldSemanticBindingBatchService batchService;

    @GetMapping("/pending-confirmations")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<List<FieldSemanticPendingBindingVO>> pending(@PathVariable Long snapshotId) {
        return Result.success(batchService.pending(snapshotId, UserContext.currentUserId()));
    }

    @PostMapping("/confirmations")
    @IamS1Resource(function = MANAGE, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<FieldSemanticBindingBatchResultVO> confirm(@PathVariable Long snapshotId,
                                                              @Valid @RequestBody FieldSemanticBindingBatchConfirmDTO request) {
        return Result.success(batchService.confirm(snapshotId, request, UserContext.currentUserId()));
    }
}
