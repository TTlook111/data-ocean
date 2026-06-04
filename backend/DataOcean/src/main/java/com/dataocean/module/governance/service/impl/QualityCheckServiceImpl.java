package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.checker.QualityChecker;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.governance.entity.vo.QualityCheckResultVO;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.service.QualityCheckService;
import com.dataocean.module.governance.service.QualityRuleService;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityCheckServiceImpl implements QualityCheckService {

    private final List<QualityChecker> checkers;
    private final QualityRuleService ruleService;
    private final MetadataSnapshotMapper snapshotMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final TableRelationMapper relationMapper;
    private final MetadataQualityIssueMapper issueMapper;

    private static final Map<String, BigDecimal> DIMENSION_WEIGHTS = Map.of(
            MetadataQualityRule.DIM_COMPLETENESS, new BigDecimal("0.30"),
            MetadataQualityRule.DIM_ACCURACY, new BigDecimal("0.25"),
            MetadataQualityRule.DIM_CONSISTENCY, new BigDecimal("0.25"),
            MetadataQualityRule.DIM_TIMELINESS, new BigDecimal("0.10"),
            MetadataQualityRule.DIM_TRACEABILITY, new BigDecimal("0.10")
    );

    @Transactional
    @Override
    public QualityCheckResultVO executeQualityCheck(Long snapshotId, List<String> dimensions, List<String> tableNames) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException(404, "快照不存在");
        }

        Set<String> targetDimensions = resolveTargetDimensions(dimensions);
        boolean isFullCheck = targetDimensions.containsAll(DIMENSION_WEIGHTS.keySet()) && CollectionUtils.isEmpty(tableNames);

        log.info("start quality check snapshotId={} datasourceId={} fullCheck={}",
                snapshotId, snapshot.getDatasourceId(), isFullCheck);

        if (isFullCheck) {
            closeOldIssues(snapshot.getDatasourceId(), snapshotId);
        }
        clearCurrentUnfinishedIssues(snapshotId, targetDimensions, tableNames);

        List<DbTableMeta> tables = loadTables(snapshotId, tableNames);
        List<DbColumnMeta> columns = loadColumns(snapshotId, tableNames);
        List<TableRelation> relations = loadRelations(snapshotId);
        List<MetadataQualityRule> rules = ruleService.listEnabledRules();

        QualityChecker.CheckContext context = new QualityChecker.CheckContext(
                snapshotId, snapshot.getDatasourceId(), tables, columns, relations, rules);

        List<MetadataQualityIssue> allIssues = new ArrayList<>();
        for (QualityChecker checker : checkers) {
            if (!targetDimensions.contains(checker.getDimension())) {
                continue;
            }
            try {
                allIssues.addAll(checker.check(context));
            } catch (Exception e) {
                log.error("quality checker failed dimension={}", checker.getDimension(), e);
            }
        }

        for (MetadataQualityIssue issue : allIssues) {
            issueMapper.insert(issue);
        }
        log.info("quality check finished snapshotId={} issueCount={}", snapshotId, allIssues.size());

        Map<String, BigDecimal> dimensionScores = calculateDimensionScores(allIssues, rules, targetDimensions);
        BigDecimal totalScore = calculateTotalScore(dimensionScores);

        if (isFullCheck) {
            snapshot.setQualityScore(totalScore);
            snapshot.setStatus(allIssues.isEmpty() ? MetadataSnapshot.STATUS_APPROVED : MetadataSnapshot.STATUS_ISSUE_FOUND);
            snapshotMapper.updateById(snapshot);
        }

        QualityCheckResultVO result = new QualityCheckResultVO();
        result.setSnapshotId(snapshotId);
        result.setQualityScore(totalScore);
        result.setDimensionScores(dimensionScores);
        result.setTotalIssues(allIssues.size());
        result.setIssueCount(countBySeverity(allIssues));
        return result;
    }

    private void closeOldIssues(Long datasourceId, Long currentSnapshotId) {
        issueMapper.update(null, new LambdaUpdateWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getDatasourceId, datasourceId)
                .ne(MetadataQualityIssue::getSnapshotId, currentSnapshotId)
                .in(MetadataQualityIssue::getStatus,
                        MetadataQualityIssue.STATUS_OPEN, MetadataQualityIssue.STATUS_CONFIRMED)
                .set(MetadataQualityIssue::getStatus, MetadataQualityIssue.STATUS_AUTO_CLOSED));
    }

    private void clearCurrentUnfinishedIssues(Long snapshotId, Set<String> dimensions, List<String> tableNames) {
        LambdaQueryWrapper<MetadataQualityIssue> qw = new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getSnapshotId, snapshotId)
                .in(MetadataQualityIssue::getDimension, dimensions)
                .in(MetadataQualityIssue::getStatus,
                        MetadataQualityIssue.STATUS_OPEN,
                        MetadataQualityIssue.STATUS_CONFIRMED);
        if (!CollectionUtils.isEmpty(tableNames)) {
            qw.in(MetadataQualityIssue::getTableName, tableNames);
        }
        issueMapper.delete(qw);
    }

    private Set<String> resolveTargetDimensions(List<String> dimensions) {
        if (CollectionUtils.isEmpty(dimensions)) {
            return new HashSet<>(DIMENSION_WEIGHTS.keySet());
        }
        Set<String> targetDimensions = new HashSet<>(dimensions);
        Set<String> unknownDimensions = targetDimensions.stream()
                .filter(dimension -> !DIMENSION_WEIGHTS.containsKey(dimension))
                .collect(Collectors.toSet());
        if (!unknownDimensions.isEmpty()) {
            throw new BusinessException("未知质量检查维度: " + String.join(",", unknownDimensions));
        }
        return targetDimensions;
    }

    private List<DbTableMeta> loadTables(Long snapshotId, List<String> tableNames) {
        LambdaQueryWrapper<DbTableMeta> qw = new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getSnapshotId, snapshotId);
        if (!CollectionUtils.isEmpty(tableNames)) {
            qw.in(DbTableMeta::getTableName, tableNames);
        }
        return tableMetaMapper.selectList(qw);
    }

    private List<DbColumnMeta> loadColumns(Long snapshotId, List<String> tableNames) {
        LambdaQueryWrapper<DbColumnMeta> qw = new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getSnapshotId, snapshotId);
        if (!CollectionUtils.isEmpty(tableNames)) {
            qw.in(DbColumnMeta::getTableName, tableNames);
        }
        return columnMetaMapper.selectList(qw);
    }

    private List<TableRelation> loadRelations(Long snapshotId) {
        return relationMapper.selectList(
                new LambdaQueryWrapper<TableRelation>()
                        .eq(TableRelation::getSnapshotId, snapshotId));
    }

    private Map<String, BigDecimal> calculateDimensionScores(List<MetadataQualityIssue> issues,
                                                             List<MetadataQualityRule> rules,
                                                             Set<String> checkedDimensions) {
        Map<Long, BigDecimal> ruleDeductions = rules.stream()
                .collect(Collectors.toMap(MetadataQualityRule::getId,
                        r -> r.getDeductionPoints() != null ? r.getDeductionPoints() : BigDecimal.ZERO,
                        (a, b) -> a));

        Map<String, BigDecimal> totalDeductions = new HashMap<>();
        for (MetadataQualityIssue issue : issues) {
            BigDecimal deduction = ruleDeductions.getOrDefault(issue.getRuleId(), BigDecimal.ZERO);
            totalDeductions.merge(issue.getDimension(), deduction, BigDecimal::add);
        }

        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        BigDecimal hundred = new BigDecimal("100");
        for (String dim : checkedDimensions) {
            BigDecimal deducted = totalDeductions.getOrDefault(dim, BigDecimal.ZERO);
            BigDecimal score = hundred.subtract(deducted).max(BigDecimal.ZERO);
            scores.put(dim, score.setScale(1, RoundingMode.HALF_UP));
        }
        return scores;
    }

    private BigDecimal calculateTotalScore(Map<String, BigDecimal> dimensionScores) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : dimensionScores.entrySet()) {
            BigDecimal weight = DIMENSION_WEIGHTS.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            total = total.add(entry.getValue().multiply(weight));
            totalWeight = totalWeight.add(weight);
        }
        if (totalWeight.compareTo(BigDecimal.ZERO) > 0 && totalWeight.compareTo(BigDecimal.ONE) < 0) {
            total = total.divide(totalWeight, 2, RoundingMode.HALF_UP);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, Integer> countBySeverity(List<MetadataQualityIssue> issues) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("HIGH", 0);
        counts.put("MEDIUM", 0);
        counts.put("LOW", 0);
        for (MetadataQualityIssue issue : issues) {
            counts.merge(issue.getSeverity(), 1, Integer::sum);
        }
        return counts;
    }
}
