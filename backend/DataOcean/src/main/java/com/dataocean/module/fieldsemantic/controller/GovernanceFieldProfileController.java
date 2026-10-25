package com.dataocean.module.fieldsemantic.controller;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldsemantic.service.GovernanceFieldProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/field-profiles")
public class GovernanceFieldProfileController {
    private final GovernanceFieldProfileService service;
    @GetMapping("/snapshots/{snapshotId}/columns/{columnId}/evidence")
    public Result<Map<String,Object>> evidence(@PathVariable Long snapshotId,@PathVariable Long columnId,@jakarta.validation.Valid com.dataocean.module.fieldsemantic.entity.query.GovernancePageQuery query){return Result.success(service.evidence(snapshotId,columnId,UserContext.currentUserId(),query));}
    @GetMapping("/snapshots/{snapshotId}/columns/{columnId}")
    public Result<Map<String,Object>> current(@PathVariable Long snapshotId,@PathVariable Long columnId){return Result.success(service.current(snapshotId,columnId,UserContext.currentUserId()));}
}
