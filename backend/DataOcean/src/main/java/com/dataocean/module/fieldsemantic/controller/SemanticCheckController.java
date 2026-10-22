package com.dataocean.module.fieldsemantic.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldsemantic.entity.vo.SemanticCheckBatchVO;
import com.dataocean.module.fieldsemantic.service.SemanticCheckService;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.system.aspect.AdminAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SEMANTIC 检查独立于来源质量检查评分与快照状态。 */
@RestController
@RequestMapping("/api/admin/semantic-checks/snapshots/{snapshotId}")
@RequiredArgsConstructor
@AdminAuditLog
public class SemanticCheckController {
    private final SemanticCheckService semanticCheckService;

    @PostMapping
    @IamS1Resource(function = "governance:check", resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<SemanticCheckBatchVO> run(@PathVariable Long snapshotId) {
        return Result.success(semanticCheckService.run(snapshotId, UserContext.currentUserId()));
    }

    @GetMapping("/latest")
    @IamS1Resource(function = "governance:check", resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<SemanticCheckBatchVO> latest(@PathVariable Long snapshotId) {
        return Result.success(semanticCheckService.latest(snapshotId, UserContext.currentUserId()));
    }
}
