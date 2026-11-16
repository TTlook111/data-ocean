package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.vo.QualityCheckBatchVO;
import com.dataocean.module.governance.entity.vo.QualityCheckBatchRuleVO;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;

public interface QualityCheckBatchService {
    com.dataocean.module.governance.entity.vo.QualityContextVO context(Long snapshotId, Long operatorId);
    record OperationStart(Long batchId, boolean owner, QualityCheckBatchVO batch) {}

    OperationStart beginOperation(Long datasourceId, Long snapshotId, String requestedScope,
                                  List<String> dimensions, List<String> tableNames, Long operatorId, String operationId);

    QualityCheckBatchVO getOperation(Long snapshotId, String operationId, Long operatorId);

    QualityCheckBatchVO resolveOperation(Long snapshotId, String operationId, Long operatorId);

    record RuleCoverage(String status, String reason, int targetCount, int checkedTargetCount, int issueCount) {}

    Long begin(Long datasourceId, Long snapshotId, String requestedScope,
               List<String> dimensions, List<String> tableNames, Long operatorId);

    void captureRules(Long batchId, List<MetadataQualityRule> frozenRules);

    void finish(Long batchId, String status, boolean complete, BigDecimal score,
                String errorSummary, Map<Long, RuleCoverage> coverageByRule);

    QualityCheckBatchVO latest(Long snapshotId);

    QualityCheckBatchVO get(Long batchId);

    /** 精确批次必须同时属于请求快照及其实际数据源，不回落为最新批次。 */
    QualityCheckBatchVO getForSnapshot(Long snapshotId, Long batchId);

    List<QualityCheckBatchRuleVO> history(Long ruleId, Long operatorUserId);
}
