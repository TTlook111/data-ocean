package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.MetadataQualityRule;

import java.util.List;

public interface QualityRuleService {

    List<MetadataQualityRule> listAllRules();

    List<MetadataQualityRule> listEnabledRules();

    List<MetadataQualityRule> listByDimension(String dimension);

    void updateEnabled(Long ruleId, boolean enabled);
}
