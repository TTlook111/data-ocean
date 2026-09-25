package com.dataocean.module.prompt.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.prompt.entity.vo.PromptTemplateVO;
import com.dataocean.module.prompt.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Prompt 内部 API 控制器
 * <p>
 * 供 Python 服务调用获取当前活跃版本的模板内容。
 * 令牌校验由 {@code InternalTokenFilter} 在 {@code /internal/**} 上统一完成
 * （启动时还会校验令牌配置本身），本类不再自行校验。
 * </p>
 */
@RestController
@RequestMapping("/internal/prompts")
@RequiredArgsConstructor
public class PromptInternalController {

    private final PromptTemplateService promptTemplateService;

    /**
     * 获取活跃版本的模板内容
     *
     * @param code 模板编码
     * @return 包含 code 和 content 的 Map
     */
    @GetMapping("/{code}")
    public Result<Map<String, Object>> getActiveContent(@PathVariable String code) {
        PromptTemplateVO template = promptTemplateService.getActiveTemplate(code);
        return Result.success(Map.of(
                "code", code,
                "content", template.getContent(),
                "versionNo", template.getCurrentVersion()
        ));
    }
}
