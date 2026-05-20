package com.dataocean.module.governance.checker;

import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 准确性校验：字段类型与命名不匹配、枚举值异常
 */
@Slf4j
@Component
public class AccuracyChecker implements QualityChecker {

    private static final Pattern TIME_PATTERN = Pattern.compile(".*_(time|date|at|datetime)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_PATTERN = Pattern.compile(".*_id$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TIME_TYPES = Set.of("datetime", "timestamp", "date", "time");
    private static final Set<String> INT_TYPES = Set.of("int", "bigint", "integer", "smallint", "tinyint", "mediumint");
    // 枚举字段的 distinct_count 阈值：超过此值认为不是枚举
    private static final long ENUM_DISTINCT_THRESHOLD = 50;

    @Override
    public String getDimension() {
        return MetadataQualityRule.DIM_ACCURACY;
    }

    @Override
    public List<MetadataQualityIssue> check(CheckContext context) {
        List<MetadataQualityIssue> issues = new ArrayList<>();
        Map<String, MetadataQualityRule> ruleMap = context.rules().stream()
                .filter(r -> getDimension().equals(r.getDimension()) && r.getEnabled() == 1)
                .collect(Collectors.toMap(MetadataQualityRule::getRuleCode, r -> r));

        MetadataQualityRule typeMismatchRule = ruleMap.get("ACCU_TYPE_NAME_MISMATCH");
        MetadataQualityRule enumAnomalyRule = ruleMap.get("ACCU_ENUM_ANOMALY");

        for (DbColumnMeta col : context.columns()) {
            String colName = col.getColumnName().toLowerCase();
            String baseType = extractBaseType(col.getDataType());

            // 时间命名但非时间类型
            if (typeMismatchRule != null && TIME_PATTERN.matcher(colName).matches()) {
                if (!TIME_TYPES.contains(baseType)) {
                    issues.add(buildIssue(context, typeMismatchRule, col.getTableName(), col.getColumnName(),
                            String.format("字段 %s.%s 命名暗示时间类型，但实际类型为 %s",
                                    col.getTableName(), col.getColumnName(), col.getDataType()),
                            "建议将字段类型修改为 DATETIME/TIMESTAMP，或修改字段命名"));
                }
            }

            // ID命名但非整数类型
            if (typeMismatchRule != null && ID_PATTERN.matcher(colName).matches()) {
                if (!INT_TYPES.contains(baseType) && !"varchar".equals(baseType)) {
                    issues.add(buildIssue(context, typeMismatchRule, col.getTableName(), col.getColumnName(),
                            String.format("字段 %s.%s 命名暗示ID类型，但实际类型为 %s",
                                    col.getTableName(), col.getColumnName(), col.getDataType()),
                            "建议将字段类型修改为 BIGINT/INT，或修改字段命名"));
                }
            }

            // 枚举值异常：字段名含 status/type/level 但 distinct_count 过高
            if (enumAnomalyRule != null && col.getDistinctCount() != null) {
                if (isLikelyEnumField(colName) && col.getDistinctCount() > ENUM_DISTINCT_THRESHOLD) {
                    issues.add(buildIssue(context, enumAnomalyRule, col.getTableName(), col.getColumnName(),
                            String.format("字段 %s.%s 命名暗示枚举类型，但去重值数量为 %d（超过阈值 %d）",
                                    col.getTableName(), col.getColumnName(), col.getDistinctCount(), ENUM_DISTINCT_THRESHOLD),
                            "建议确认该字段是否为枚举字段，如是则检查数据质量"));
                }
            }
        }

        log.info("准确性校验完成，发现 {} 个问题", issues.size());
        return issues;
    }

    private String extractBaseType(String dataType) {
        if (dataType == null) return "";
        // "bigint unsigned" → "bigint", "varchar(255)" → "varchar"
        return dataType.toLowerCase().split("[\\s(]")[0];
    }

    private boolean isLikelyEnumField(String colName) {
        return colName.contains("status") || colName.contains("type")
                || colName.contains("level") || colName.contains("state")
                || colName.contains("category") || colName.contains("kind");
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
