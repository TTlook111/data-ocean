package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.MetadataQualityRule;

import java.util.List;

/**
 * 元数据质量规则服务。
 */
public interface QualityRuleService {

    /**
     * 查询全部质量规则。
     *
     * @return 质量规则列表
     */
    List<MetadataQualityRule> listAllRules();

    /**
     * 查询启用中的质量规则。
     *
     * @return 启用规则列表
     */
    List<MetadataQualityRule> listEnabledRules();

    /**
     * 更新质量规则启用状态。
     *
     * @param ruleId   规则 ID
     * @param enabled  是否启用
     */
    void updateEnabled(Long ruleId, boolean enabled);
}
