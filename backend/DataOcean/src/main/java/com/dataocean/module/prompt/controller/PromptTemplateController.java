package com.dataocean.module.prompt.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.prompt.entity.dto.*;
import com.dataocean.module.prompt.entity.vo.PromptEffectivenessVO;
import com.dataocean.module.prompt.entity.vo.PromptTemplateVO;
import com.dataocean.module.prompt.entity.vo.PromptVersionVO;
import com.dataocean.module.prompt.service.PromptTemplateService;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板管理控制器
 * <p>
 * 提供 Prompt 模板的列表、详情、更新、版本历史、审批流程和回滚 API。
 * 管理接口需要 prompt:manage 权限，审批接口需要 prompt:approve 权限。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/prompt-templates")
@RequiredArgsConstructor
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
    @PreAuthorize("hasAnyAuthority('prompt:manage', 'prompt:approve', '*')")
    public Result<Page<PromptTemplateVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(promptTemplateService.listTemplates(page, pageSize));
    }

    /**
     * 按模板版本查看 Prompt 效果分析。
     */
    @GetMapping("/effectiveness")
    @PreAuthorize("hasAnyAuthority('prompt:manage', 'prompt:approve', '*')")
    public Result<List<PromptEffectivenessVO>> effectiveness(@RequestParam(defaultValue = "30") int days) {
        return Result.success(promptTemplateService.getEffectiveness(days));
    }

    /**
     * 根据编码获取模板详情
     *
     * @param code 模板编码
     * @return 模板详情
     */
    @GetMapping("/{code}")
    @PreAuthorize("hasAnyAuthority('prompt:manage', 'prompt:approve', '*')")
    public Result<PromptTemplateVO> get(@PathVariable String code) {
        return Result.success(promptTemplateService.getTemplate(code));
    }

    /**
     * 更新模板内容（自动创建新版本，状态变为 DRAFT）
     *
     * @param code    模板编码
     * @param request 更新请求体
     * @return 更新后的模板
     */
    @PutMapping("/{code}")
    @PreAuthorize("hasAnyAuthority('prompt:manage', '*')")
    public Result<PromptTemplateVO> update(@PathVariable String code,
                                           @Valid @RequestBody PromptTemplateUpdateDTO request) {
        return Result.success("更新成功", promptTemplateService.updateTemplate(code, request));
    }

    /**
     * 提交审核（DRAFT → PENDING_REVIEW）
     *
     * @param code 模板编码
     * @return 更新后的模板
     */
    @PostMapping("/{code}/submit")
    @PreAuthorize("hasAnyAuthority('prompt:manage', '*')")
    public Result<PromptTemplateVO> submitForReview(@PathVariable String code) {
        return Result.success("已提交审核", promptTemplateService.submitForReview(code));
    }

    /**
     * 审核通过（PENDING_REVIEW → APPROVED）
     *
     * @param code    模板编码
     * @param request 审核备注（可选）
     * @return 更新后的模板
     */
    @PostMapping("/{code}/approve")
    @PreAuthorize("hasAnyAuthority('prompt:approve', '*')")
    public Result<PromptTemplateVO> approve(@PathVariable String code,
                                            @RequestBody(required = false) Map<String, String> request) {
        String changeSummary = request != null ? request.get("changeSummary") : null;
        return Result.success("审核通过", promptTemplateService.approve(code, changeSummary));
    }

    /**
     * 审核拒绝（PENDING_REVIEW → REJECTED）
     *
     * @param code    模板编码
     * @param request 拒绝原因
     * @return 更新后的模板
     */
    @PostMapping("/{code}/reject")
    @PreAuthorize("hasAnyAuthority('prompt:approve', '*')")
    public Result<PromptTemplateVO> reject(@PathVariable String code,
                                           @RequestBody Map<String, String> request) {
        String rejectReason = request.get("rejectReason");
        return Result.success("已拒绝", promptTemplateService.reject(code, rejectReason));
    }

    /**
     * 获取模板的版本历史
     *
     * @param code 模板编码
     * @return 版本列表
     */
    @GetMapping("/{code}/versions")
    @PreAuthorize("hasAnyAuthority('prompt:manage', 'prompt:approve', '*')")
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
    @PreAuthorize("hasAnyAuthority('prompt:manage', '*')")
    public Result<PromptTemplateVO> rollback(@PathVariable String code,
                                             @Valid @RequestBody PromptRollbackDTO request) {
        return Result.success("回滚成功", promptTemplateService.rollback(code, request));
    }
}
