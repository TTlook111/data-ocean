package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.audit.entity.AlertRule;
import com.dataocean.module.audit.mapper.AlertRuleMapper;
import com.dataocean.module.audit.service.AlertRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 告警规则服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertRuleServiceImpl implements AlertRuleService {

    private final AlertRuleMapper alertRuleMapper;

    @Override
    public Page<AlertRule> listRules(int page, int pageSize) {
        return alertRuleMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<AlertRule>().orderByDesc(AlertRule::getCreatedAt));
    }

    @Override
    public AlertRule createRule(AlertRule rule) {
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.insert(rule);
        log.info("创建告警规则 metric={} threshold={}", rule.getMetric(), rule.getThreshold());
        return rule;
    }

    @Override
    public AlertRule updateRule(Long id, AlertRule rule) {
        AlertRule existing = alertRuleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "告警规则不存在");
        }
        existing.setMetric(rule.getMetric());
        existing.setThreshold(rule.getThreshold());
        existing.setOperator(rule.getOperator());
        existing.setNotificationType(rule.getNotificationType());
        existing.setEnabled(rule.getEnabled());
        existing.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(existing);
        return existing;
    }

    @Override
    public void toggleRule(Long id) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(404, "告警规则不存在");
        }
        rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()));
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(rule);
        log.info("告警规则状态切换 id={} enabled={}", id, rule.getEnabled());
    }
}
