package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.governance.mapper.MetadataQualityRuleMapper;
import com.dataocean.module.governance.service.QualityRuleService;
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
    public void updateEnabled(Long ruleId, boolean enabled) {
        MetadataQualityRule rule = ruleMapper.selectById(ruleId);
        if (rule != null) {
            rule.setEnabled(enabled ? 1 : 0);
            ruleMapper.updateById(rule);
        }
    }
}
