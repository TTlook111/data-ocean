package com.dataocean.module.prompt.controller;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.prompt.entity.query.PromptInvocationQuery;
import com.dataocean.module.prompt.entity.vo.PromptInvocationVO;
import com.dataocean.module.prompt.entity.vo.PromptSceneVO;
import com.dataocean.module.prompt.service.PromptInvocationService;
import com.dataocean.module.prompt.service.PromptSceneRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/admin/prompt-templates")
@RequiredArgsConstructor
public class PromptInvocationController {
    private final PromptInvocationService service;
    @GetMapping("/scenes")
    @IamS1Global("prompt:view")
    public Result<List<PromptSceneVO>> scenes() { return Result.success(PromptSceneRegistry.SCENES); }
    @GetMapping("/invocations")
    @IamS1Global("prompt:view")
    public Result<Page<PromptInvocationVO>> list(PromptInvocationQuery query) { return Result.success(service.list(query)); }
}
