package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.vo.QualityCheckBatchVO;
import com.dataocean.module.governance.entity.vo.QualityCheckBatchRuleVO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.List;

public interface QualityCheckBatchService {
    record RuleCoverage(String status, String reason, int targetCount, int checkedTargetCount, int issueCount) {}

    Long begin(Long datasourceId, Long snapshotId, String requestedScope,
               List<String> dimensions, List<String> tableNames, Long operatorId);

    void captureRules(Long batchId, List<MetadataQualityRule> frozenRules);

    void finish(Long batchId, String status, boolean complete, BigDecimal score,
                String errorSummary, Map<Long, RuleCoverage> coverageByRule);

    QualityCheckBatchVO latest(Long snapshotId);

    QualityCheckBatchVO get(Long batchId);

    List<QualityCheckBatchRuleVO> history(Long ruleId, Long operatorUserId);
}
