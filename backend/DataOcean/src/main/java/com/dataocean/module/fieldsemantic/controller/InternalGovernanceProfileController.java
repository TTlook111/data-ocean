package com.dataocean.module.fieldsemantic.controller;
import com.dataocean.common.result.Result;
import com.dataocean.module.fieldsemantic.service.GovernanceFieldProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/internal/iam-s1/query/tasks/{taskId}/field-profiles")
public class InternalGovernanceProfileController {
    private final GovernanceFieldProfileService service;
    public record Resource(@NotBlank String tableName,@NotEmpty @Size(max=300) List<@NotBlank String> columns){}
    public record Request(@NotEmpty @Size(max=100) List<@Valid Resource> resources){}
    @PostMapping public Result<Map<String,Object>> read(@PathVariable String taskId,@Valid @RequestBody Request request){
        List<String> fields=request.resources().stream().flatMap(t->t.columns().stream().map(c->t.tableName()+"."+c)).toList();
        return Result.success(service.read(taskId,fields));
    }
}
