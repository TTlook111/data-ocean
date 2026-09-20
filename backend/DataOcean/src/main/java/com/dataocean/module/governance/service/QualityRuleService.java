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
    /**
     * 启用或停用全局质量规则。
     *
     * <p>全局规则没有 datasourceId，启停影响所有数据源，因此只允许受保护系统管理员执行——
     * 非系统管理员即使在某个负责源上有 {@code governance:rule:manage} 也不能改全局规则。
     * 表/列治理状态不受此限制。</p>
     */
    void updateEnabled(Long ruleId, boolean enabled, Long operatorUserId);
}
