package com.dataocean.module.fieldsemantic.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldsemantic.entity.dto.BusinessKnowledgeDraftDTO;
import com.dataocean.module.fieldsemantic.service.BusinessKnowledgeService;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/business-knowledge")
public class BusinessKnowledgeController {
    private final BusinessKnowledgeService service;
    public record Action(@NotNull Integer expectedVersion,@NotBlank String action,@Size(max=8000) String note) {}
    @GetMapping("/{id}/history")
    @IamS1ScopedList("knowledge:view")
    public Result<List<Map<String,Object>>> history(@PathVariable Long id){return Result.success(service.history(id,UserContext.currentUserId()));}
    @GetMapping("/{id}/source-status")
    @IamS1ScopedList("knowledge:view")
    public Result<Map<String,Object>> source(@PathVariable Long id){return Result.success(service.sourceStatus(id,UserContext.currentUserId()));}
    public record SourceRequest(@NotEmpty @Size(max=2000) List<@NotBlank String> dependencies){}
    @PostMapping("/{id}/source-status")
    @IamS1ScopedList("knowledge:view")
    public Result<Map<String,Object>> previewSource(@PathVariable Long id,@Valid @RequestBody SourceRequest request){return Result.success(service.sourceStatus(id,UserContext.currentUserId(),request.dependencies()));}
    @GetMapping("/snapshots/{snapshotId}")
    @IamS1ScopedList("knowledge:view")
    public Result<List<Map<String,Object>>> list(@PathVariable Long snapshotId){return Result.success(service.list(snapshotId,UserContext.currentUserId()));}
    @PostMapping("/snapshots/{snapshotId}/drafts")
    @IamS1ScopedList("knowledge:manage")
    public Result<Map<String,Object>> save(@PathVariable Long snapshotId,@Valid @RequestBody BusinessKnowledgeDraftDTO request){return Result.success(service.save(snapshotId,request,UserContext.currentUserId()));}
    @PostMapping("/snapshots/{snapshotId}/candidates")
    @IamS1ScopedList("knowledge:manage")
    public Result<Map<String,Object>> candidate(@PathVariable Long snapshotId,@Valid @RequestBody BusinessKnowledgeDraftDTO request){return Result.success(service.generate(snapshotId,request,UserContext.currentUserId()));}
    @PostMapping("/{id}/actions")
    public Result<Void> action(@PathVariable Long id,@Valid @RequestBody Action request){service.action(id,request.expectedVersion(),request.action(),request.note(),UserContext.currentUserId());return Result.success(null);}
}
