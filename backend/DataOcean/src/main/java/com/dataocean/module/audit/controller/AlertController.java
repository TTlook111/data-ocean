package com.dataocean.module.audit.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.audit.entity.AlertRule;
import com.dataocean.module.audit.entity.dto.AlertRuleDTO;
import com.dataocean.module.audit.mapper.AlertRuleMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

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

    private final AlertRuleMapper alertRuleMapper;

    /** 告警规则列表 */
    @GetMapping
    public Result<Page<AlertRule>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<AlertRule> result = alertRuleMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<AlertRule>().orderByDesc(AlertRule::getCreatedAt));
        return Result.success(result);
    }

    /** 创建告警规则 */
    @PostMapping
    public Result<AlertRule> createRule(@Valid @RequestBody AlertRuleDTO dto) {
        AlertRule rule = new AlertRule();
        BeanUtils.copyProperties(dto, rule);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.insert(rule);
        return Result.success("创建成功", rule);
    }

    /** 更新告警规则 */
    @PutMapping("/{id}")
    public Result<AlertRule> updateRule(@PathVariable Long id, @Valid @RequestBody AlertRuleDTO dto) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            return Result.error(404, "告警规则不存在");
        }
        BeanUtils.copyProperties(dto, rule);
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(rule);
        return Result.success("更新成功", rule);
    }

    /** 启用/禁用告警规则 */
    @PatchMapping("/{id}/toggle")
    public Result<Void> toggleRule(@PathVariable Long id) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            return Result.error(404, "告警规则不存在");
        }
        rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()));
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(rule);
        return Result.success(rule.getEnabled() ? "已启用" : "已禁用", null);
    }
}
