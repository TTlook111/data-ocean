package com.dataocean.module.fieldsemantic.controller;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldsemantic.entity.query.GovernancePageQuery;
import com.dataocean.module.fieldsemantic.service.GovernanceFeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/governance-feedback")
public class GovernanceFeedbackController {
    private final GovernanceFeedbackService service;
    @GetMapping("/{id}/history")
    public Result<List<Map<String,Object>>> history(@PathVariable Long id){return Result.success(service.history(id,UserContext.currentUserId()));}
    public record Review(@NotNull Integer expectedVersion,boolean approved,Long columnMetaId,
                         @Pattern(regexp="UNKNOWN|FIELD_MEANING|FIELD_USAGE|RELATION|METRIC|SQL|PRESENTATION") String reasonCode,
                         @NotBlank @Size(max=8000) String note,
                         @Size(max=2000) String scenario,@Size(max=4000) String expectedResult){}
    @GetMapping("/datasources/{datasourceId}")
    public Result<Map<String,Object>> list(@PathVariable Long datasourceId,@Valid GovernancePageQuery query){return Result.success(service.list(datasourceId,UserContext.currentUserId(),query));}
    @PostMapping("/{id}/review")
    public Result<Void> review(@PathVariable Long id,@Valid @RequestBody Review request){service.review(id,request.expectedVersion(),request.approved(),request.columnMetaId(),request.reasonCode(),request.note(),UserContext.currentUserId(),Map.of("scenario",Objects.toString(request.scenario(),""),"expectedResult",Objects.toString(request.expectedResult(),"")));return Result.success(null);}
    @PostMapping("/{id}/verification")
    public Result<Void> verify(@PathVariable Long id,@Valid @RequestBody com.dataocean.module.fieldsemantic.entity.dto.GovernanceFeedbackVerificationDTO request){service.verifyResolution(id,request,UserContext.currentUserId());return Result.success(null);}
}
