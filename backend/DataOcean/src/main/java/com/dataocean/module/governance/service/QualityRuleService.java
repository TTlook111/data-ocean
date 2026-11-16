package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.dto.QualityRuleDTO;
import com.dataocean.module.governance.entity.query.QualityRuleQuery;
import com.dataocean.module.governance.entity.vo.QualityRuleVO;
import com.dataocean.module.governance.entity.vo.QualityRuleRevisionVO;

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

    List<MetadataQualityRule> listEnabledRules(Long datasourceId);

    com.baomidou.mybatisplus.extension.plugins.pagination.Page<QualityRuleVO> listRules(
            QualityRuleQuery query, Long operatorUserId);

    QualityRuleVO getRule(Long ruleId, Long operatorUserId, boolean includeDeleted);

    List<QualityRuleRevisionVO> history(Long ruleId, Long operatorUserId);

    QualityRuleVO createRule(QualityRuleDTO request, Long operatorUserId);

    QualityRuleVO updateRule(Long ruleId, QualityRuleDTO request, Integer expectedVersion, Long operatorUserId);

    QualityRuleVO copyRule(Long ruleId, Integer expectedVersion, Long operatorUserId);

    void deleteRule(Long ruleId, Integer expectedVersion, Long operatorUserId);

    void restoreRule(Long ruleId, Integer expectedVersion, Long operatorUserId);

    /**
     * 按客户端原版本启用或停用规则；状态变更同样推进配置版本。
     *
     * <p>全局规则没有 datasourceId，启停影响所有数据源，因此只允许受保护系统管理员执行——
     * 非系统管理员即使在某个负责源上有 {@code governance:rule:manage} 也不能改全局规则。
     * 表/列治理状态不受此限制。</p>
     */
    void updateEnabled(Long ruleId, boolean enabled, Integer expectedVersion, Long operatorUserId);
}
