package com.dataocean.module.prompt.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.prompt.entity.dto.*;
import com.dataocean.module.prompt.service.PromptTemplateService;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Prompt 模板管理控制器
 * <p>
 * 提供 Prompt 模板的列表、详情、更新、版本历史和回滚 API。
 * 所有接口需要 prompt:manage 权限。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/prompt-templates")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('prompt:manage')")
@AdminAuditLog
@Slf4j
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    /**
     * 分页查询模板列表
     *
     * @param page     页码，默认 1
     * @param pageSize 每页大小，默认 20
     * @return 分页模板列表
     */
    @GetMapping
    public Result<Page<PromptTemplateVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(promptTemplateService.listTemplates(page, pageSize));
    }

    /**
     * 根据编码获取模板详情
     *
     * @param code 模板编码
     * @return 模板详情
     */
    @GetMapping("/{code}")
    public Result<PromptTemplateVO> get(@PathVariable String code) {
        return Result.success(promptTemplateService.getTemplate(code));
    }

    /**
     * 更新模板内容（自动创建新版本）
     *
     * @param code    模板编码
     * @param request 更新请求体
     * @return 更新后的模板
     */
    @PutMapping("/{code}")
    public Result<PromptTemplateVO> update(@PathVariable String code,
                                           @Valid @RequestBody PromptTemplateUpdateDTO request) {
        return Result.success("更新成功", promptTemplateService.updateTemplate(code, request));
    }

    /**
     * 获取模板的版本历史
     *
     * @param code 模板编码
     * @return 版本列表
     */
    @GetMapping("/{code}/versions")
    public Result<List<PromptVersionVO>> versions(@PathVariable String code) {
        return Result.success(promptTemplateService.getVersionHistory(code));
    }

    /**
     * 回滚到指定版本
     *
     * @param code    模板编码
     * @param request 回滚请求体
     * @return 回滚后的模板
     */
    @PostMapping("/{code}/rollback")
    public Result<PromptTemplateVO> rollback(@PathVariable String code,
                                             @Valid @RequestBody PromptRollbackDTO request) {
        return Result.success("回滚成功", promptTemplateService.rollback(code, request));
    }
}
