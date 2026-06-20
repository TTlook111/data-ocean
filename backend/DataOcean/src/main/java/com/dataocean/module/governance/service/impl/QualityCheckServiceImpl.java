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

/**
 * 元数据质量检查服务实现类。
 * <p>
 * 负责执行元数据质量检查，基于扣分制算法计算各维度质量分数。
 * 支持五个质量维度：完整性（30%）、准确性（25%）、一致性（25%）、时效性（10%）、可追溯性（10%）。
 * </p>
 * <p>
 * 核心算法：
 * <ul>
 *   <li>每个质量规则定义扣分点数（deductionPoints）</li>
 *   <li>检查器发现问题后，按规则扣分</li>
 *   <li>维度得分 = 100 - 该维度所有问题的扣分总和（最低0分）</li>
 *   <li>总分 = 各维度得分 × 权重的加权平均</li>
 * </ul>
 * </p>
 *
 * @author DataOcean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityCheckServiceImpl implements QualityCheckService {

    /** 质量检查器列表，按 Spring 注入顺序执行 */
    private final List<QualityChecker> checkers;
    /** 质量规则服务，提供启用的规则列表 */
    private final QualityRuleService ruleService;
    /** 元数据快照 Mapper */
    private final MetadataSnapshotMapper snapshotMapper;
    /** 数据库表元数据 Mapper */
    private final DbTableMetaMapper tableMetaMapper;
    /** 数据库列元数据 Mapper */
    private final DbColumnMetaMapper columnMetaMapper;
    /** 表关系 Mapper */
    private final TableRelationMapper relationMapper;
    /** 质量问题 Mapper */
    private final MetadataQualityIssueMapper issueMapper;

    /**
     * 维度权重配置。
     * <p>
     * 权重总和为 1.0，用于加权计算总分。
     * 当部分维度被检查时，权重会自动归一化处理。
     * </p>
     */
    private static final Map<String, BigDecimal> DIMENSION_WEIGHTS = Map.of(
            MetadataQualityRule.DIM_COMPLETENESS, new BigDecimal("0.30"),  // 完整性：表/字段注释、主键等
            MetadataQualityRule.DIM_ACCURACY, new BigDecimal("0.25"),      // 准确性：数据类型、长度等
            MetadataQualityRule.DIM_CONSISTENCY, new BigDecimal("0.25"),   // 一致性：命名规范、外键关系等
            MetadataQualityRule.DIM_TIMELINESS, new BigDecimal("0.10"),    // 时效性：数据新鲜度等
            MetadataQualityRule.DIM_TRACEABILITY, new BigDecimal("0.10")   // 可追溯性：血缘、变更历史等
    );

    /**
     * 执行元数据质量检查。
     * <p>
     * 实现逻辑：
     * 1. 校验快照存在性
     * 2. 解析目标维度（空则检查全部维度）
     * 3. 判断是否为全量检查（全部维度 + 无表名过滤）
     * 4. 全量检查时关闭历史快照的旧问题
     * 5. 清除当前快照未完成的问题（为重新检查腾出空间）
     * 6. 加载表、列、关系、规则等检查上下文
     * 7. 逐个执行质量检查器，收集问题
     * 8. 批量插入问题记录
     * 9. 计算各维度得分和总分
     * 10. 全量检查时更新快照质量分和状态
     * </p>
     *
     * @param snapshotId  快照ID
     * @param dimensions  要检查的维度列表，为空则检查全部维度
     * @param tableNames  要检查的表名列表，为空则检查全部表
     * @return 质量检查结果，包含总分、各维度得分、问题统计
     */
    @Transactional
    @Override
    public QualityCheckResultVO executeQualityCheck(Long snapshotId, List<String> dimensions, List<String> tableNames) {
        // 1. 校验快照存在性
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException(404, "快照不存在");
        }

        // 2. 解析目标维度（空则返回全部维度，非空则校验合法性）
        Set<String> targetDimensions = resolveTargetDimensions(dimensions);

        // 3. 判断是否为全量检查：包含全部维度 且 无表名过滤
        //    全量检查会触发：关闭历史问题 + 更新快照质量分和状态
        boolean isFullCheck = targetDimensions.containsAll(DIMENSION_WEIGHTS.keySet()) && CollectionUtils.isEmpty(tableNames);

        log.info("start quality check snapshotId={} datasourceId={} fullCheck={}",
                snapshotId, snapshot.getDatasourceId(), isFullCheck);

        // 4. 全量检查时，关闭该数据源下其他快照的旧问题（OPEN/CONFIRMED → AUTO_CLOSED）
        if (isFullCheck) {
            closeOldIssues(snapshot.getDatasourceId(), snapshotId);
        }

        // 5. 清除当前快照中未完成的问题（OPEN/CONFIRMED），为重新检查腾出空间
        clearCurrentUnfinishedIssues(snapshotId, targetDimensions, tableNames);

        // 6. 加载检查上下文：表、列、关系、规则
        List<DbTableMeta> tables = loadTables(snapshotId, tableNames);
        List<DbColumnMeta> columns = loadColumns(snapshotId, tableNames);
        List<TableRelation> relations = loadRelations(snapshotId);
        List<MetadataQualityRule> rules = ruleService.listEnabledRules();

        // 7. 构建检查上下文对象，传递给所有检查器
        QualityChecker.CheckContext context = new QualityChecker.CheckContext(
                snapshotId, snapshot.getDatasourceId(), tables, columns, relations, rules);

        // 8. 逐个执行质量检查器，收集所有问题
        //    每个检查器负责一个维度，失败不影响其他检查器
        List<MetadataQualityIssue> allIssues = new ArrayList<>();
        for (QualityChecker checker : checkers) {
            // 跳过不在目标维度内的检查器
            if (!targetDimensions.contains(checker.getDimension())) {
                continue;
            }
            try {
                allIssues.addAll(checker.check(context));
            } catch (Exception e) {
                // 单个检查器失败不阻断整体流程，记录错误日志继续
                log.error("quality checker failed dimension={}", checker.getDimension(), e);
            }
        }

        // 9. 批量插入问题记录到数据库
        for (MetadataQualityIssue issue : allIssues) {
            issueMapper.insert(issue);
        }
        log.info("quality check finished snapshotId={} issueCount={}", snapshotId, allIssues.size());

        // 10. 计算各维度得分和加权总分
        Map<String, BigDecimal> dimensionScores = calculateDimensionScores(allIssues, rules, targetDimensions);
        BigDecimal totalScore = calculateTotalScore(dimensionScores);

        // 11. 全量检查时更新快照质量分和状态
        if (isFullCheck) {
            snapshot.setQualityScore(totalScore);
            // 无问题则标记为已批准，否则标记为存在问题
            snapshot.setStatus(allIssues.isEmpty() ? MetadataSnapshot.STATUS_APPROVED : MetadataSnapshot.STATUS_ISSUE_FOUND);
            snapshotMapper.updateById(snapshot);
        }

        // 12. 构建返回结果
        QualityCheckResultVO result = new QualityCheckResultVO();
        result.setSnapshotId(snapshotId);
        result.setQualityScore(totalScore);
        result.setDimensionScores(dimensionScores);
        result.setTotalIssues(allIssues.size());
        result.setIssueCount(countBySeverity(allIssues));
        return result;
    }

    /**
     * 关闭历史快照的旧质量问题。
     * <p>
     * 在全量检查时调用，将该数据源下其他快照的 OPEN/CONFIRMED 状态问题
     * 批量更新为 AUTO_CLOSED，表示这些问题已被新检查取代。
     * </p>
     *
     * @param datasourceId     数据源ID
     * @param currentSnapshotId 当前快照ID（排除不处理）
     */
    private void closeOldIssues(Long datasourceId, Long currentSnapshotId) {
        issueMapper.update(null, new LambdaUpdateWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getDatasourceId, datasourceId)        // 同一数据源
                .ne(MetadataQualityIssue::getSnapshotId, currentSnapshotId)     // 排除当前快照
                .in(MetadataQualityIssue::getStatus,
                        MetadataQualityIssue.STATUS_OPEN,                      // 状态为"待处理"
                        MetadataQualityIssue.STATUS_CONFIRMED)                 // 或"已确认"
                .set(MetadataQualityIssue::getStatus, MetadataQualityIssue.STATUS_AUTO_CLOSED));  // 更新为"自动关闭"
    }

    /**
     * 清除当前快照中未完成的问题。
     * <p>
     * 在重新检查前调用，删除当前快照中指定维度和表的 OPEN/CONFIRMED 状态问题，
     * 为新检查结果腾出空间。只清除未完成的问题，已解决或已关闭的问题保留。
     * </p>
     *
     * @param snapshotId  快照ID
     * @param dimensions  要清除的维度集合
     * @param tableNames  要清除的表名列表，为空则清除所有表
     */
    private void clearCurrentUnfinishedIssues(Long snapshotId, Set<String> dimensions, List<String> tableNames) {
        LambdaQueryWrapper<MetadataQualityIssue> qw = new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getSnapshotId, snapshotId)            // 当前快照
                .in(MetadataQualityIssue::getDimension, dimensions)             // 指定维度
                .in(MetadataQualityIssue::getStatus,
                        MetadataQualityIssue.STATUS_OPEN,                      // 状态为"待处理"
                        MetadataQualityIssue.STATUS_CONFIRMED);                // 或"已确认"
        // 如果指定了表名，只清除这些表的问题
        if (!CollectionUtils.isEmpty(tableNames)) {
            qw.in(MetadataQualityIssue::getTableName, tableNames);
        }
        issueMapper.delete(qw);
    }

    /**
     * 解析目标检查维度。
     * <p>
     * 如果传入的维度列表为空，则返回全部维度（完整性、准确性、一致性、时效性、可追溯性）。
     * 如果传入了维度列表，则校验每个维度是否合法，不合法则抛出异常。
     * </p>
     *
     * @param dimensions 用户指定的维度列表，为空则检查全部维度
     * @return 目标维度集合
     * @throws BusinessException 如果包含未知维度
     */
    private Set<String> resolveTargetDimensions(List<String> dimensions) {
        // 空列表表示检查全部维度
        if (CollectionUtils.isEmpty(dimensions)) {
            return new HashSet<>(DIMENSION_WEIGHTS.keySet());
        }
        Set<String> targetDimensions = new HashSet<>(dimensions);
        // 校验维度合法性：过滤出不在 DIMENSION_WEIGHTS 中的维度
        Set<String> unknownDimensions = targetDimensions.stream()
                .filter(dimension -> !DIMENSION_WEIGHTS.containsKey(dimension))
                .collect(Collectors.toSet());
        if (!unknownDimensions.isEmpty()) {
            throw new BusinessException("未知质量检查维度: " + String.join(",", unknownDimensions));
        }
        return targetDimensions;
    }

    /**
     * 加载指定快照的表元数据。
     * <p>
     * 支持按表名过滤，如果 tableNames 为空则加载全部表。
     * </p>
     *
     * @param snapshotId 快照ID
     * @param tableNames 要加载的表名列表，为空则加载全部
     * @return 表元数据列表
     */
    private List<DbTableMeta> loadTables(Long snapshotId, List<String> tableNames) {
        LambdaQueryWrapper<DbTableMeta> qw = new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getSnapshotId, snapshotId);
        // 如果指定了表名，只加载这些表
        if (!CollectionUtils.isEmpty(tableNames)) {
            qw.in(DbTableMeta::getTableName, tableNames);
        }
        return tableMetaMapper.selectList(qw);
    }

    /**
     * 加载指定快照的列元数据。
     * <p>
     * 支持按表名过滤，如果 tableNames 为空则加载全部列。
     * </p>
     *
     * @param snapshotId 快照ID
     * @param tableNames 要加载的表名列表，为空则加载全部
     * @return 列元数据列表
     */
    private List<DbColumnMeta> loadColumns(Long snapshotId, List<String> tableNames) {
        LambdaQueryWrapper<DbColumnMeta> qw = new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getSnapshotId, snapshotId);
        // 如果指定了表名，只加载这些表的列
        if (!CollectionUtils.isEmpty(tableNames)) {
            qw.in(DbColumnMeta::getTableName, tableNames);
        }
        return columnMetaMapper.selectList(qw);
    }

    /**
     * 加载指定快照的表关系（外键关系）。
     *
     * @param snapshotId 快照ID
     * @return 表关系列表
     */
    private List<TableRelation> loadRelations(Long snapshotId) {
        return relationMapper.selectList(
                new LambdaQueryWrapper<TableRelation>()
                        .eq(TableRelation::getSnapshotId, snapshotId));
    }

    /**
     * 计算各维度的质量得分。
     * <p>
     * 基于扣分制算法：
     * <ul>
     *   <li>每个规则定义扣分点数（deductionPoints）</li>
     *   <li>发现问题时，按规则的扣分点数累计到对应维度</li>
     *   <li>维度得分 = 100 - 该维度所有问题的扣分总和（最低0分）</li>
     * </ul>
     * </p>
     *
     * @param issues            检查发现的问题列表
     * @param rules             质量规则列表（包含扣分点数定义）
     * @param checkedDimensions 已检查的维度集合
     * @return 各维度得分，key为维度名称，value为得分（0-100，保留1位小数）
     */
    private Map<String, BigDecimal> calculateDimensionScores(List<MetadataQualityIssue> issues,
                                                             List<MetadataQualityRule> rules,
                                                             Set<String> checkedDimensions) {
        // 第一步：构建规则ID → 扣分点数的映射
        // 使用 Stream 的 Collectors.toMap 将规则列表转换为 Map<ruleId, deductionPoints>
        // 合并函数 (a, b) -> a 处理重复 key 的情况（保留第一个）
        Map<Long, BigDecimal> ruleDeductions = rules.stream()
                .collect(Collectors.toMap(
                        MetadataQualityRule::getId,                                    // key: 规则ID
                        r -> r.getDeductionPoints() != null ? r.getDeductionPoints() : BigDecimal.ZERO,  // value: 扣分点数，null则为0
                        (a, b) -> a));                                                  // 合并函数：重复key保留第一个

        // 第二步：按维度累计扣分
        // 遍历所有问题，根据问题关联的规则ID查找扣分点数，累加到对应维度
        Map<String, BigDecimal> totalDeductions = new HashMap<>();
        for (MetadataQualityIssue issue : issues) {
            BigDecimal deduction = ruleDeductions.getOrDefault(issue.getRuleId(), BigDecimal.ZERO);
            totalDeductions.merge(issue.getDimension(), deduction, BigDecimal::add);  // 按维度累加扣分
        }

        // 第三步：计算各维度最终得分
        // 得分 = 100 - 扣分总和，最低为0分
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        BigDecimal hundred = new BigDecimal("100");
        for (String dim : checkedDimensions) {
            BigDecimal deducted = totalDeductions.getOrDefault(dim, BigDecimal.ZERO);
            BigDecimal score = hundred.subtract(deducted).max(BigDecimal.ZERO);  // 扣分后最低为0
            scores.put(dim, score.setScale(1, RoundingMode.HALF_UP));            // 保留1位小数
        }
        return scores;
    }

    /**
     * 计算加权总分。
     * <p>
     * 总分 = Σ(维度得分 × 维度权重)。
     * 当只检查部分维度时，权重会自动归一化：
     * 总分 = Σ(维度得分 × 维度权重) / Σ(已检查维度的权重)。
     * </p>
     *
     * @param dimensionScores 各维度得分
     * @return 加权总分（0-100，保留2位小数）
     */
    private BigDecimal calculateTotalScore(Map<String, BigDecimal> dimensionScores) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        // 遍历各维度，计算加权得分和权重总和
        for (Map.Entry<String, BigDecimal> entry : dimensionScores.entrySet()) {
            BigDecimal weight = DIMENSION_WEIGHTS.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            total = total.add(entry.getValue().multiply(weight));      // 累加：维度得分 × 权重
            totalWeight = totalWeight.add(weight);                      // 累加权重
        }
        // 如果只检查了部分维度（权重总和 < 1.0），则归一化处理
        // 例如：只检查完整性和准确性，权重分别为0.30和0.25，总权重0.55
        // 归一化后总分 = (完整性得分×0.30 + 准确性得分×0.25) / 0.55
        if (totalWeight.compareTo(BigDecimal.ZERO) > 0 && totalWeight.compareTo(BigDecimal.ONE) < 0) {
            total = total.divide(totalWeight, 2, RoundingMode.HALF_UP);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 按严重级别统计问题数量。
     * <p>
     * 初始化 HIGH、MEDIUM、LOW 三个级别的计数为0，
     * 然后遍历问题列表累加各级别的数量。
     * </p>
     *
     * @param issues 问题列表
     * @return 各严重级别的问题数量，key为级别名称，value为数量
     */
    private Map<String, Integer> countBySeverity(List<MetadataQualityIssue> issues) {
        // 初始化三个级别的计数为0，使用 LinkedHashMap 保持插入顺序
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("HIGH", 0);
        counts.put("MEDIUM", 0);
        counts.put("LOW", 0);
        // 遍历问题，按严重级别累加计数
        for (MetadataQualityIssue issue : issues) {
            counts.merge(issue.getSeverity(), 1, Integer::sum);
        }
        return counts;
    }
}
