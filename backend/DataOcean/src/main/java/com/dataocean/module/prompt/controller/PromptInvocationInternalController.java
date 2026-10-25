package com.dataocean.module.prompt.controller;
import com.dataocean.common.result.Result;
import com.dataocean.module.prompt.entity.dto.PromptInvocationDTO;
import com.dataocean.module.prompt.service.PromptInvocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
/** 复用 /internal/** 的共享令牌保护，不接入浏览器。 */
@RestController
@RequestMapping("/internal/prompts")
@RequiredArgsConstructor
public class PromptInvocationInternalController {
    private final PromptInvocationService service;
    @PostMapping("/invocations")
    public Result<Void> record(@Valid @RequestBody PromptInvocationDTO request) {
        service.record(request);
        return Result.success();
    }
}
