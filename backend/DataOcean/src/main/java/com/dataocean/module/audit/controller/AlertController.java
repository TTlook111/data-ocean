package com.dataocean.module.audit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.audit.entity.AlertRule;
import com.dataocean.module.audit.entity.dto.AlertRuleDTO;
import com.dataocean.module.audit.service.AlertRuleService;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;

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
@AdminAuditLog
@Slf4j
public class AlertController {

    /** 查看告警规则：运行监控只读。 */
    private static final String VIEW_FUNCTION = "system:runtime:view";
    /** 创建、修改、启停告警规则，以及重置连接池。 */
    private static final String MANAGE_FUNCTION = "system:runtime:manage";

    private final AlertRuleService alertRuleService;

    /** 告警规则列表 */
    @GetMapping
    @IamS1Global(VIEW_FUNCTION)
    public Result<Page<AlertRule>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(alertRuleService.listRules(page, pageSize));
    }

    /** 创建告警规则 */
    @PostMapping
    @IamS1Global(MANAGE_FUNCTION)
    public Result<AlertRule> createRule(@Valid @RequestBody AlertRuleDTO dto) {
        AlertRule rule = new AlertRule();
        BeanUtils.copyProperties(dto, rule);
        return Result.success("创建成功", alertRuleService.createRule(rule));
    }

    /** 更新告警规则 */
    @PutMapping("/{id}")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<AlertRule> updateRule(@PathVariable Long id, @Valid @RequestBody AlertRuleDTO dto) {
        AlertRule rule = new AlertRule();
        BeanUtils.copyProperties(dto, rule);
        return Result.success("更新成功", alertRuleService.updateRule(id, rule));
    }

    /** 启用/禁用告警规则 */
    @PatchMapping("/{id}/toggle")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<Void> toggleRule(@PathVariable Long id) {
        alertRuleService.toggleRule(id);
        return Result.success("操作成功", null);
    }
}
