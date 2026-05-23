package com.dataocean.module.governance.checker;

import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.TableRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 可追溯性校验：外键关系缺失、孤立表
 */
@Slf4j
@Component
public class TraceabilityChecker implements QualityChecker {

    private static final Pattern FK_PATTERN = Pattern.compile(".*_id$", Pattern.CASE_INSENSITIVE);

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDimension() {
        return MetadataQualityRule.DIM_TRACEABILITY;
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

        MetadataQualityRule fkMissingRule = ruleMap.get("TRACE_FK_MISSING");
        MetadataQualityRule isolatedRule = ruleMap.get("TRACE_ISOLATED_TABLE");

        // 收集已有关联关系的字段
        Set<String> relatedColumns = new HashSet<>();
        Set<String> relatedTables = new HashSet<>();
        for (TableRelation rel : context.relations()) {
            relatedColumns.add(rel.getSourceTable() + "." + rel.getSourceColumn());
            relatedColumns.add(rel.getTargetTable() + "." + rel.getTargetColumn());
            relatedTables.add(rel.getSourceTable());
            relatedTables.add(rel.getTargetTable());
        }

        // 外键关系缺失：有 xxx_id 命名但无关联关系定义
        if (fkMissingRule != null) {
            for (DbColumnMeta col : context.columns()) {
                // 排除主键本身
                if (col.getIsPrimaryKey() != null && col.getIsPrimaryKey() == 1) continue;
                if (!FK_PATTERN.matcher(col.getColumnName()).matches()) continue;

                String key = col.getTableName() + "." + col.getColumnName();
                if (!relatedColumns.contains(key)) {
                    issues.add(buildIssue(context, fkMissingRule, col.getTableName(), col.getColumnName(),
                            String.format("字段 %s.%s 命名暗示外键关系，但未定义关联",
                                    col.getTableName(), col.getColumnName()),
                            "建议确认该字段是否为外键，如是则补充关联关系定义"));
                }
            }
        }

        // 孤立表检测：无任何关联关系的表
        if (isolatedRule != null) {
            for (DbTableMeta table : context.tables()) {
                if (!relatedTables.contains(table.getTableName())) {
                    issues.add(buildIssue(context, isolatedRule, table.getTableName(), null,
                            String.format("表 %s 无任何关联关系，为孤立表", table.getTableName()),
                            "建议确认该表是否与其他表存在业务关联，如有则补充关系定义"));
                }
            }
        }

        log.info("可追溯性校验完成，发现 {} 个问题", issues.size());
        return issues;
    }

    private MetadataQualityIssue buildIssue(CheckContext ctx, MetadataQualityRule rule,
                                            String tableName, String columnName,
                                            String description, String suggestion) {
        MetadataQualityIssue issue = new MetadataQualityIssue();
        issue.setSnapshotId(ctx.snapshotId());
        issue.setDatasourceId(ctx.datasourceId());
        issue.setRuleId(rule.getId());
        issue.setDimension(rule.getDimension());
        issue.setSeverity(rule.getSeverity());
        issue.setTableName(tableName);
        issue.setColumnName(columnName);
        issue.setIssueDescription(description);
        issue.setSuggestion(suggestion);
        issue.setStatus(MetadataQualityIssue.STATUS_OPEN);
        return issue;
    }
}
