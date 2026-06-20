package com.dataocean.module.governance.checker;

import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.metadata.entity.DbTableMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 时效性校验器。
 * <p>
 * 从两个维度检测元数据时效性：
 * <ul>
 *   <li>快照过期（TIME_SNAPSHOT_EXPIRED）：
 *     <ul>
 *       <li>快照创建时间超过阈值（默认7天）视为过期</li>
 *       <li>过期的快照可能不反映最新的数据库结构</li>
 *     </ul>
 *   </li>
 *   <li>表长期无数据更新（TIME_TABLE_STALE）：
 *     <ul>
 *       <li>表的 update_time 字段显示最后更新时间超过阈值</li>
 *       <li>可能表示该表已废弃或数据同步异常</li>
 *     </ul>
 *   </li>
 * </ul>
 * </p>
 *
 * @author DataOcean
 */
@Slf4j
@Component
public class TimelinessChecker implements QualityChecker {

    // 快照超过此天数视为过期
    private static final long SNAPSHOT_EXPIRE_DAYS = 7;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDimension() {
        return MetadataQualityRule.DIM_TIMELINESS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MetadataQualityIssue> check(CheckContext context) {
        List<MetadataQualityIssue> issues = new ArrayList<>();
        Map<String, MetadataQualityRule> ruleMap = context.rules().stream()
                .filter(r -> getDimension().equals(r.getDimension()) && r.getEnabled() == 1)
                .collect(Collectors.toMap(MetadataQualityRule::getRuleCode, r -> r));

        MetadataQualityRule snapshotExpiredRule = ruleMap.get("TIME_SNAPSHOT_EXPIRED");
        MetadataQualityRule tableNoUpdateRule = ruleMap.get("TIME_TABLE_NO_UPDATE");

        // 快照过期检测：基于快照创建时间判断
        if (snapshotExpiredRule != null && !context.tables().isEmpty()) {
            DbTableMeta firstTable = context.tables().get(0);
            if (firstTable.getCreatedAt() != null) {
                long daysSinceSync = ChronoUnit.DAYS.between(firstTable.getCreatedAt(), LocalDateTime.now());
                if (daysSinceSync > SNAPSHOT_EXPIRE_DAYS) {
                    MetadataQualityIssue issue = new MetadataQualityIssue();
                    issue.setSnapshotId(context.snapshotId());
                    issue.setDatasourceId(context.datasourceId());
                    issue.setRuleId(snapshotExpiredRule.getId());
                    issue.setDimension(snapshotExpiredRule.getDimension());
                    issue.setSeverity(snapshotExpiredRule.getSeverity());
                    issue.setTableName("*");
                    issue.setIssueDescription(String.format("快照距上次同步已超过 %d 天（阈值 %d 天），元数据可能已过期",
                            daysSinceSync, SNAPSHOT_EXPIRE_DAYS));
                    issue.setSuggestion("建议重新执行元数据同步，获取最新的数据库结构信息");
                    issue.setStatus(MetadataQualityIssue.STATUS_OPEN);
                    issues.add(issue);
                }
            }
        }

        // 表长期无更新检测：基于行数估算为0且有字段的表
        if (tableNoUpdateRule != null) {
            for (DbTableMeta table : context.tables()) {
                if (table.getRowCountEstimate() != null && table.getRowCountEstimate() == 0
                        && "TABLE".equals(table.getTableType())) {
                    MetadataQualityIssue issue = new MetadataQualityIssue();
                    issue.setSnapshotId(context.snapshotId());
                    issue.setDatasourceId(context.datasourceId());
                    issue.setRuleId(tableNoUpdateRule.getId());
                    issue.setDimension(tableNoUpdateRule.getDimension());
                    issue.setSeverity(tableNoUpdateRule.getSeverity());
                    issue.setTableName(table.getTableName());
                    issue.setIssueDescription(String.format("表 %s 行数为 0，可能长期无数据写入", table.getTableName()));
                    issue.setSuggestion("建议确认该表是否仍在使用，如已废弃可标记为 DEPRECATED");
                    issue.setStatus(MetadataQualityIssue.STATUS_OPEN);
                    issues.add(issue);
                }
            }
        }

        log.info("时效性校验完成，发现 {} 个问题", issues.size());
        return issues;
    }
}
