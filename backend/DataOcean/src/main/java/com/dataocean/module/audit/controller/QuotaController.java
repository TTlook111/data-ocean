package com.dataocean.module.audit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.audit.entity.QuotaPolicy;
import com.dataocean.module.audit.entity.dto.QuotaPolicyDTO;
import com.dataocean.module.audit.entity.vo.QuotaCheckVO;
import com.dataocean.module.audit.entity.vo.LlmUsageStatsVO;
import com.dataocean.module.audit.service.LlmUsageService;
import com.dataocean.module.audit.service.QuotaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 配额管理控制器
 * <p>
 * 提供配额策略的 CRUD 和用户配额检查 API。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/quotas")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit:view')")
@Slf4j
public class QuotaController {

    private final QuotaService quotaService;
    private final LlmUsageService llmUsageService;

    /** 配额策略列表 */
    @GetMapping
    public Result<Page<QuotaPolicy>> listPolicies(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(quotaService.listPolicies(page, pageSize));
    }

    /** 创建配额策略 */
    @PostMapping
    public Result<QuotaPolicy> createPolicy(@Valid @RequestBody QuotaPolicyDTO dto) {
        QuotaPolicy policy = new QuotaPolicy();
        BeanUtils.copyProperties(dto, policy);
        return Result.success("创建成功", quotaService.createPolicy(policy));
    }

    /** 更新配额策略 */
    @PutMapping("/{id}")
    public Result<QuotaPolicy> updatePolicy(@PathVariable Long id, @Valid @RequestBody QuotaPolicyDTO dto) {
        QuotaPolicy policy = new QuotaPolicy();
        BeanUtils.copyProperties(dto, policy);
        return Result.success("更新成功", quotaService.updatePolicy(id, policy));
    }

    /** 检查用户配额 */
    @GetMapping("/check/{userId}")
    public Result<QuotaCheckVO> checkQuota(@PathVariable Long userId) {
        return Result.success(quotaService.checkQuota(userId));
    }

    /** LLM 使用统计 */
    @GetMapping("/llm-usage")
    public Result<LlmUsageStatsVO> getLlmUsageStats(@RequestParam(defaultValue = "30") int days) {
        return Result.success(llmUsageService.getUsageStats(days));
    }
}
