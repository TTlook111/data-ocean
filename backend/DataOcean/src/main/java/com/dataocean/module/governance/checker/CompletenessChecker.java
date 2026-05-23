package com.dataocean.module.governance.checker;

import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 完整性校验：表注释缺失、字段注释缺失、主键缺失
 */
@Slf4j
@Component
public class CompletenessChecker implements QualityChecker {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDimension() {
        return MetadataQualityRule.DIM_COMPLETENESS;
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

        MetadataQualityRule tableCommentRule = ruleMap.get("COMP_TABLE_COMMENT_MISSING");
        MetadataQualityRule columnCommentRule = ruleMap.get("COMP_COLUMN_COMMENT_MISSING");
        MetadataQualityRule pkRule = ruleMap.get("COMP_PRIMARY_KEY_MISSING");

        // 检测表注释缺失
        if (tableCommentRule != null) {
            for (DbTableMeta table : context.tables()) {
                if (!StringUtils.hasText(table.getTableComment())) {
                    issues.add(buildIssue(context, tableCommentRule, table.getTableName(), null,
                            String.format("表 %s 缺少表注释", table.getTableName()),
                            "建议补充表注释，描述该表的业务含义"));
                }
            }
        }

        // 检测字段注释缺失
        if (columnCommentRule != null) {
            for (DbColumnMeta col : context.columns()) {
                if (!StringUtils.hasText(col.getColumnComment())) {
                    issues.add(buildIssue(context, columnCommentRule, col.getTableName(), col.getColumnName(),
                            String.format("字段 %s.%s 缺少字段注释", col.getTableName(), col.getColumnName()),
                            "建议补充字段注释，说明该字段的取值含义"));
                }
            }
        }

        // 检测主键缺失
        if (pkRule != null) {
            Set<String> tablesWithPk = context.columns().stream()
                    .filter(c -> c.getIsPrimaryKey() != null && c.getIsPrimaryKey() == 1)
                    .map(DbColumnMeta::getTableName)
                    .collect(Collectors.toSet());

            for (DbTableMeta table : context.tables()) {
                if (!tablesWithPk.contains(table.getTableName())) {
                    issues.add(buildIssue(context, pkRule, table.getTableName(), null,
                            String.format("表 %s 缺少主键定义", table.getTableName()),
                            "建议为表添加主键，确保数据唯一性和查询性能"));
                }
            }
        }

        log.info("完整性校验完成，发现 {} 个问题", issues.size());
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
