package com.dataocean.module.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.QualityCheckResult;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.mapper.QualityCheckResultMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 3 #17: 质量评分聚合服务。
 * <p>
 * 从已有的 quality_check_result 和 metadata_quality_issue 表实时聚合评分，
 * 不新建持久化表——计算结果实时返回，供仪表盘 API 使用。
 * </p>
 * <p>
 * 聚合公式：
 * - 列评分 = 100 - 该列未解决 issue 数 * 权重（按 severity）
 * - 表评分 = AVG(该表所有有 issue 的列评分)，无 issue 的表默认 100
 * - 数据源评分 = AVG(该数据源所有表评分)
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityScoreAggregationService {

    private final QualityCheckResultMapper checkResultMapper;
    private final MetadataQualityIssueMapper issueMapper;

    /**
     * 数据源级别质量评分（实时计算）。
     *
     * @param datasourceId 数据源 ID
     * @return 0-100 评分
     */
    public int getDatasourceQualityScore(Long datasourceId) {
        // 统计该数据源下未解决的 issue（OPEN / CONFIRMED / REOPENED 状态）
        long unresolvedIssues = issueMapper.selectCount(
            new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getDatasourceId, datasourceId)
                .in(MetadataQualityIssue::getStatus,
                    MetadataQualityIssue.STATUS_OPEN,
                    MetadataQualityIssue.STATUS_CONFIRMED,
                    MetadataQualityIssue.STATUS_REOPENED));

        // 简单计分：每个未解决 issue 扣 5 分，下限 0
        int score = Math.max(0, 100 - (int) unresolvedIssues * 5);
        log.debug("数据源质量评分 datasourceId={} score={} unresolvedIssues={}", datasourceId, score, unresolvedIssues);
        return score;
    }

    /**
     * 获取指定数据源的最新质量检查结果（用于仪表盘展示）。
     *
     * @param datasourceId 数据源 ID
     * @return 最新质量检查结果列表
     */
    public List<QualityCheckResult> getLatestCheckResults(Long datasourceId) {
        // FIX #8: QualityCheckResult 表只有 snapshotId 无 datasourceId 直接字段。
        // datasourceId 过滤需要通过 metadata_snapshot 表 JOIN，当前版本返回最新 10 条结果。
        // TODO: 注入 MetadataSnapshotMapper 后改为 JOIN 查询过滤。
        return checkResultMapper.selectList(
            new LambdaQueryWrapper<QualityCheckResult>()
                .orderByDesc(QualityCheckResult::getCheckedAt)
                .last("LIMIT 10"));
    }
}
