package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.governance.mapper.MetadataQualityRuleMapper;
import com.dataocean.module.governance.service.QualityRuleService;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 元数据质量规则服务实现。
 */
@Service
@RequiredArgsConstructor
public class QualityRuleServiceImpl implements QualityRuleService {

    private final MetadataQualityRuleMapper ruleMapper;
    private final IamS1AuthorizationResolver authorizationResolver;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MetadataQualityRule> listAllRules() {
        return ruleMapper.selectList(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MetadataQualityRule> listEnabledRules() {
        return ruleMapper.selectList(
                new LambdaQueryWrapper<MetadataQualityRule>()
                        .eq(MetadataQualityRule::getEnabled, 1)
        );
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void updateEnabled(Long ruleId, boolean enabled, Long operatorUserId) {
        // 全局质量规则没有 datasourceId，启停会影响**所有**数据源。
        // 所以这里不能只看 governance:rule:manage：非系统管理员即使在某些负责源上拥有该功能，
        // 也不允许修改全局规则。表/列治理状态仍由“功能 + 目标负责源”放行，不受此限制。
        if (operatorUserId == null || !authorizationResolver.isSystemAdmin(operatorUserId)) {
            throw new BusinessException(403, "启用或停用全局质量规则只能由受保护的系统管理员执行");
        }
        MetadataQualityRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException(404, "质量规则不存在");
        }
        rule.setEnabled(enabled ? 1 : 0);
        ruleMapper.updateById(rule);
    }
}
