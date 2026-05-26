package com.dataocean.module.audit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.audit.entity.AlertRule;
import com.dataocean.module.audit.entity.dto.AlertRuleDTO;
import com.dataocean.module.audit.service.AlertRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 告警规则控制器
 * <p>
 * 提供告警规则的 CRUD 和启用/禁用 API。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/alert-rules")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit:view')")
@Slf4j
public class AlertController {

    private final AlertRuleService alertRuleService;

    /** 告警规则列表 */
    @GetMapping
    public Result<Page<AlertRule>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(alertRuleService.listRules(page, pageSize));
    }

    /** 创建告警规则 */
    @PostMapping
    public Result<AlertRule> createRule(@Valid @RequestBody AlertRuleDTO dto) {
        AlertRule rule = new AlertRule();
        BeanUtils.copyProperties(dto, rule);
        return Result.success("创建成功", alertRuleService.createRule(rule));
    }

    /** 更新告警规则 */
    @PutMapping("/{id}")
    public Result<AlertRule> updateRule(@PathVariable Long id, @Valid @RequestBody AlertRuleDTO dto) {
        AlertRule rule = new AlertRule();
        BeanUtils.copyProperties(dto, rule);
        return Result.success("更新成功", alertRuleService.updateRule(id, rule));
    }

    /** 启用/禁用告警规则 */
    @PatchMapping("/{id}/toggle")
    public Result<Void> toggleRule(@PathVariable Long id) {
        alertRuleService.toggleRule(id);
        return Result.success("操作成功", null);
    }
}
