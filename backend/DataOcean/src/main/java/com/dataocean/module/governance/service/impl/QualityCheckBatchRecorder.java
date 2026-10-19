package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.po.QualityCheckBatch;
import com.dataocean.module.governance.entity.po.QualityCheckBatchRule;
import com.dataocean.module.governance.entity.vo.QualityCheckBatchRuleVO;
import com.dataocean.module.governance.entity.vo.QualityCheckBatchVO;
import com.dataocean.module.governance.mapper.QualityCheckBatchMapper;
import com.dataocean.module.governance.mapper.QualityCheckBatchRuleMapper;
import com.dataocean.module.governance.mapper.QualityCheckResultMapper;
import com.dataocean.module.governance.service.QualityCheckBatchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 在独立事务中落执行批次，确保调用事务回滚时失败批次仍可追溯。 */
@Service
@RequiredArgsConstructor
public class QualityCheckBatchRecorder implements QualityCheckBatchService {
    private final QualityCheckBatchMapper batchMapper;
    private final QualityCheckBatchRuleMapper batchRuleMapper;
    private final QualityCheckResultMapper checkResultMapper;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long begin(Long datasourceId, Long snapshotId, String requestedScope,
                      List<String> dimensions, List<String> tableNames, Long operatorId) {
        QualityCheckBatch batch = new QualityCheckBatch();
        batch.setDatasourceId(datasourceId);
        batch.setSnapshotId(snapshotId);
        batch.setRequestedScope(requestedScope);
        batch.setRequestedDimensionsJson(writeJson(dimensions));
        batch.setRequestedTableNamesJson(writeJson(tableNames));
        batch.setStatus("RUNNING");
        batch.setComplete(0);
        batch.setTotalScore(null);
        batch.setRuleCount(0);
        batch.setExecutedRuleCount(0);
        batch.setFailedRuleCount(0);
        batch.setNotApplicableRuleCount(0);
        batch.setStartedAt(LocalDateTime.now());
        batch.setCreatedBy(operatorId);
        batchMapper.insert(batch);
        batch.setRuleCount(0);
        batchMapper.updateById(batch);
        return batch.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void captureRules(Long batchId, List<MetadataQualityRule> frozenRules) {
        for (MetadataQualityRule source : frozenRules) {
            batchRuleMapper.insert(snapshotRule(batchId, source));
        }
        QualityCheckBatch batch = batchMapper.selectById(batchId);
        if (batch != null) {
            batch.setRuleCount(frozenRules.size());
            batchMapper.updateById(batch);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long batchId, String status, boolean complete, BigDecimal score,
                       String errorSummary, Map<Long, RuleCoverage> coverageByRule) {
        QualityCheckBatch batch = batchMapper.selectById(batchId);
        if (batch == null) return;
        int executed = 0;
        int failed = 0;
        int notApplicable = 0;
        List<QualityCheckBatchRule> rules = batchRuleMapper.selectList(new LambdaQueryWrapper<QualityCheckBatchRule>()
                .eq(QualityCheckBatchRule::getBatchId, batchId));
        for (QualityCheckBatchRule rule : rules) {
            RuleCoverage coverage = coverageByRule.get(rule.getRuleId());
            if (coverage == null) {
                rule.setStatus("NOT_EXECUTED");
                if (!StringUtils.hasText(rule.getReason())) rule.setReason("批次未完成该规则的检查");
                batchRuleMapper.updateById(rule);
                continue;
            }
            rule.setStatus(coverage.status());
            rule.setReason(coverage.reason());
            rule.setTargetCount(coverage.targetCount());
            rule.setCheckedTargetCount(coverage.checkedTargetCount());
            rule.setIssueCount(coverage.issueCount());
            batchRuleMapper.updateById(rule);
            if ("SUCCESS".equals(coverage.status())) executed++;
            else if ("FAILURE".equals(coverage.status())) failed++;
            else if ("NOT_APPLICABLE".equals(coverage.status())) notApplicable++;
        }
        batch.setStatus(status);
        batch.setComplete(complete ? 1 : 0);
        batch.setTotalScore(complete ? score : null);
        batch.setExecutedRuleCount(executed);
        batch.setFailedRuleCount(failed);
        batch.setNotApplicableRuleCount(notApplicable);
        batch.setErrorSummary(errorSummary);
        batch.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
    }

    public QualityCheckBatchVO latest(Long snapshotId) {
        QualityCheckBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<QualityCheckBatch>()
                .eq(QualityCheckBatch::getSnapshotId, snapshotId)
                .orderByDesc(QualityCheckBatch::getId).last("LIMIT 1"));
        if (batch != null) return toVO(batch);
        return legacyResult(snapshotId);
    }

    public QualityCheckBatchVO get(Long batchId) {
        QualityCheckBatch batch = batchMapper.selectById(batchId);
        return batch == null ? null : toVO(batch);
    }

    @Transactional(readOnly = true)
    @Override
    public List<QualityCheckBatchRuleVO> history(Long ruleId) {
        List<QualityCheckBatchRule> rows = batchRuleMapper.selectList(new LambdaQueryWrapper<QualityCheckBatchRule>()
                .eq(QualityCheckBatchRule::getRuleId, ruleId)
                .orderByDesc(QualityCheckBatchRule::getBatchId)
                .orderByDesc(QualityCheckBatchRule::getRuleVersion));
        Map<Long, QualityCheckBatch> batches = new HashMap<>();
        for (QualityCheckBatchRule row : rows) {
            batches.computeIfAbsent(row.getBatchId(), batchMapper::selectById);
        }
        return rows.stream().map(row -> {
            QualityCheckBatchRuleVO vo = toRuleVO(row);
            QualityCheckBatch batch = batches.get(row.getBatchId());
            if (batch != null) {
                vo.setBatchStartedAt(batch.getStartedAt());
                vo.setBatchStatus(batch.getStatus());
            }
            return vo;
        }).toList();
    }

    private QualityCheckBatchVO legacyResult(Long snapshotId) {
        var latest = checkResultMapper.selectOne(new LambdaQueryWrapper<com.dataocean.module.governance.entity.po.QualityCheckResult>()
                .eq(com.dataocean.module.governance.entity.po.QualityCheckResult::getSnapshotId, snapshotId)
                .isNull(com.dataocean.module.governance.entity.po.QualityCheckResult::getCheckBatchId)
                .orderByDesc(com.dataocean.module.governance.entity.po.QualityCheckResult::getCheckedAt)
                .last("LIMIT 1"));
        if (latest == null) return null;
        QualityCheckBatchVO vo = new QualityCheckBatchVO();
        vo.setDatasourceId(null);
        vo.setSnapshotId(snapshotId);
        vo.setStatus("LEGACY");
        vo.setComplete(false);
        vo.setRuleCount(null);
        vo.setExecutedRuleCount(null);
        vo.setFailedRuleCount(null);
        vo.setNotApplicableRuleCount(null);
        vo.setStartedAt(latest.getCheckedAt());
        vo.setFinishedAt(latest.getCheckedAt());
        vo.setLegacyLimited(true);
        vo.setErrorSummary("历史结果没有批次覆盖和规则配置版本记录，无法证明完整检查范围。");
        vo.setRules(List.of());
        return vo;
    }

    private QualityCheckBatchVO toVO(QualityCheckBatch batch) {
        QualityCheckBatchVO vo = new QualityCheckBatchVO();
        vo.setId(batch.getId());
        vo.setDatasourceId(batch.getDatasourceId());
        vo.setSnapshotId(batch.getSnapshotId());
        vo.setRequestedScope(batch.getRequestedScope());
        vo.setRequestedDimensions(readList(batch.getRequestedDimensionsJson()));
        vo.setRequestedTableNames(readList(batch.getRequestedTableNamesJson()));
        vo.setStatus(batch.getStatus());
        vo.setComplete(Integer.valueOf(1).equals(batch.getComplete()));
        vo.setTotalScore(batch.getTotalScore());
        vo.setRuleCount(batch.getRuleCount());
        vo.setExecutedRuleCount(batch.getExecutedRuleCount());
        vo.setFailedRuleCount(batch.getFailedRuleCount());
        vo.setNotApplicableRuleCount(batch.getNotApplicableRuleCount());
        vo.setErrorSummary(batch.getErrorSummary());
        vo.setStartedAt(batch.getStartedAt());
        vo.setFinishedAt(batch.getFinishedAt());
        vo.setLegacyLimited(false);
        Map<String, java.math.BigDecimal> dimensions = new java.util.LinkedHashMap<>();
        checkResultMapper.selectList(new LambdaQueryWrapper<com.dataocean.module.governance.entity.po.QualityCheckResult>()
                .eq(com.dataocean.module.governance.entity.po.QualityCheckResult::getCheckBatchId, batch.getId())
                .orderByAsc(com.dataocean.module.governance.entity.po.QualityCheckResult::getId))
                .forEach(result -> dimensions.put(result.getDimension(), result.getScore()));
        vo.setDimensionScores(dimensions);
        List<QualityCheckBatchRule> rows = batchRuleMapper.selectList(new LambdaQueryWrapper<QualityCheckBatchRule>()
                .eq(QualityCheckBatchRule::getBatchId, batch.getId())
                .orderByAsc(QualityCheckBatchRule::getId));
        List<QualityCheckBatchRuleVO> ruleVOs = new ArrayList<>();
        for (QualityCheckBatchRule row : rows) ruleVOs.add(toRuleVO(row));
        vo.setRules(ruleVOs);
        return vo;
    }

    private QualityCheckBatchRuleVO toRuleVO(QualityCheckBatchRule row) {
        QualityCheckBatchRuleVO vo = new QualityCheckBatchRuleVO();
        vo.setBatchId(row.getBatchId());
        vo.setRuleId(row.getRuleId());
        vo.setRuleCode(row.getRuleCode());
        vo.setTemplateCode(row.getTemplateCode());
        vo.setRuleVersion(row.getRuleVersion());
        vo.setRuleName(row.getRuleName());
        vo.setDescription(row.getDescription());
        vo.setDimension(row.getDimension());
        vo.setSeverity(row.getSeverity());
        vo.setDeductionPoints(row.getDeductionPoints());
        vo.setScopeType(row.getScopeType());
        vo.setDatasourceId(row.getDatasourceId());
        vo.setTargetTableName(row.getTargetTableName());
        vo.setTargetColumnName(row.getTargetColumnName());
        vo.setParameters(readMap(row.getParametersJson()));
        vo.setStatus(row.getStatus());
        vo.setReason(row.getReason());
        vo.setTargetCount(row.getTargetCount());
        vo.setCheckedTargetCount(row.getCheckedTargetCount());
        vo.setIssueCount(row.getIssueCount());
        return vo;
    }

    private QualityCheckBatchRule snapshotRule(Long batchId, MetadataQualityRule source) {
        QualityCheckBatchRule row = new QualityCheckBatchRule();
        row.setBatchId(batchId);
        row.setRuleId(source.getId());
        row.setRuleCode(source.getRuleCode());
        row.setTemplateCode(source.getTemplateCode());
        row.setRuleVersion(source.getConfigVersion() == null ? 1 : source.getConfigVersion());
        row.setRuleName(source.getRuleName());
        row.setDescription(source.getDescription());
        row.setDimension(source.getDimension());
        row.setSeverity(source.getSeverity());
        row.setDeductionPoints(source.getDeductionPoints());
        row.setScopeType(source.getScopeType() == null ? "GLOBAL" : source.getScopeType());
        row.setDatasourceId(source.getDatasourceId());
        row.setTargetTableName(source.getTargetTableName());
        row.setTargetColumnName(source.getTargetColumnName());
        row.setParametersJson(source.getParametersJson());
        row.setStatus("NOT_EXECUTED");
        row.setTargetCount(0);
        row.setCheckedTargetCount(0);
        row.setIssueCount(0);
        return row;
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(500, "无法记录质量检查范围");
        }
    }

    private List<String> readList(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String value) {
        if (!StringUtils.hasText(value)) return Map.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of("_invalid", "历史参数无法解析");
        }
    }
}
