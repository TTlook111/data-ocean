package com.dataocean.module.fieldsemantic.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldsemantic.service.GovernanceTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/governance-tasks")
public class GovernanceTaskController {
    private final GovernanceTaskService service;
    @GetMapping("/rules")
    public Result<java.util.List<Map<String,Object>>> rules(){return Result.success(com.dataocean.module.fieldsemantic.service.impl.GovernanceRuleCatalog.rules());}
    @PostMapping("/snapshots/{snapshotId}/discover")
    public Result<Map<String, Object>> discover(@PathVariable Long snapshotId, @RequestParam(defaultValue="true") boolean aiFirst) {
        return Result.success(service.discover(snapshotId, UserContext.currentUserId(), aiFirst));
    }
    @GetMapping("/snapshots/{snapshotId}")
    public Result<Map<String, Object>> list(@PathVariable Long snapshotId, @jakarta.validation.Valid com.dataocean.module.fieldsemantic.entity.query.GovernancePageQuery query) {
        return Result.success(service.list(snapshotId, UserContext.currentUserId(), query.getPageNo().intValue(), query.getPageSize().intValue()));
    }
    @PostMapping("/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) { service.retry(id, UserContext.currentUserId()); return Result.success(null); }
    @PostMapping("/snapshots/{snapshotId}/pause")
    public Result<Void> pause(@PathVariable Long snapshotId){service.pause(snapshotId,UserContext.currentUserId());return Result.success(null);}
}
