package com.dataocean.module.audit.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.audit.entity.AlertRule;

/**
 * 告警规则服务接口
 * <p>
 * 提供告警规则的 CRUD 和启用/禁用功能。
 * </p>
 */
public interface AlertRuleService {

    /**
     * 分页查询告警规则列表
     */
    Page<AlertRule> listRules(int page, int pageSize);

    /**
     * 创建告警规则
     */
    AlertRule createRule(AlertRule rule);

    /**
     * 更新告警规则
     */
    AlertRule updateRule(Long id, AlertRule rule);

    /**
     * 切换告警规则启用/禁用状态
     */
    void toggleRule(Long id);
}
