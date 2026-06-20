package com.dataocean.module.governance.checker;

import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一致性校验器。
 * <p>
 * 从两个维度检测元数据一致性：
 * <ul>
 *   <li>同名字段跨表类型不一致（CONS_TYPE_INCONSISTENCY）：
 *     <ul>
 *       <li>例如：user_id 在 orders 表中是 bigint，在 logs 表中是 varchar</li>
 *       <li>可能导致 JOIN 查询时类型转换错误或性能问题</li>
 *     </ul>
 *   </li>
 *   <li>同名字段注释冲突（CONS_COMMENT_CONFLICT）：
 *     <ul>
 *       <li>例如：user_id 在 orders 表中注释是"用户ID"，在 logs 表中注释是"操作人ID"</li>
 *       <li>可能导致业务理解混乱</li>
 *     </ul>
 *   </li>
 * </ul>
 * </p>
 * <p>
 * 校验策略：
 * <ul>
 *   <li>按字段名分组，收集所有表中同名字段的类型和注释</li>
 *   <li>检查类型集合大小是否大于1（存在不同类型）</li>
 *   <li>检查注释集合大小是否大于1（存在不同注释）</li>
 * </ul>
 * </p>
 *
 * @author DataOcean
 */
@Slf4j
@Component
public class ConsistencyChecker implements QualityChecker {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDimension() {
        return MetadataQualityRule.DIM_CONSISTENCY;
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

        MetadataQualityRule typeMismatchRule = ruleMap.get("CONS_CROSS_TABLE_TYPE_MISMATCH");
        MetadataQualityRule commentConflictRule = ruleMap.get("CONS_CROSS_TABLE_COMMENT_CONFLICT");

        // 按字段名分组
        Map<String, List<DbColumnMeta>> columnsByName = new HashMap<>();
        for (DbColumnMeta col : context.columns()) {
            columnsByName.computeIfAbsent(col.getColumnName().toLowerCase(), k -> new ArrayList<>()).add(col);
        }

        for (Map.Entry<String, List<DbColumnMeta>> entry : columnsByName.entrySet()) {
            List<DbColumnMeta> sameNameCols = entry.getValue();
            if (sameNameCols.size() < 2) continue;

            // 跨表类型不一致检测
            if (typeMismatchRule != null) {
                checkTypeMismatch(context, typeMismatchRule, sameNameCols, issues);
            }

            // 跨表注释冲突检测
            if (commentConflictRule != null) {
                checkCommentConflict(context, commentConflictRule, sameNameCols, issues);
            }
        }

        log.info("一致性校验完成，发现 {} 个问题", issues.size());
        return issues;
    }

    private void checkTypeMismatch(CheckContext ctx, MetadataQualityRule rule,
                                   List<DbColumnMeta> cols, List<MetadataQualityIssue> issues) {
        // 提取基础类型，忽略长度差异（varchar(50) 和 varchar(200) 视为一致）
        Map<String, List<DbColumnMeta>> byBaseType = new HashMap<>();
        for (DbColumnMeta col : cols) {
            String dataType = col.getDataType();
            if (dataType == null) dataType = "unknown";
            String baseType = dataType.toLowerCase().split("[\\s(]")[0];
            byBaseType.computeIfAbsent(baseType, k -> new ArrayList<>()).add(col);
        }

        if (byBaseType.size() > 1) {
            // 存在类型不一致，取第一个字段作为报告对象
            DbColumnMeta first = cols.get(0);
            String tables = cols.stream()
                    .map(c -> c.getTableName() + "(" + c.getDataType() + ")")
                    .collect(Collectors.joining(", "));

            MetadataQualityIssue issue = new MetadataQualityIssue();
            issue.setSnapshotId(ctx.snapshotId());
            issue.setDatasourceId(ctx.datasourceId());
            issue.setRuleId(rule.getId());
            issue.setDimension(rule.getDimension());
            issue.setSeverity(rule.getSeverity());
            issue.setTableName(first.getTableName());
            issue.setColumnName(first.getColumnName());
            issue.setIssueDescription(String.format("字段 %s 在多表中类型不一致: %s",
                    first.getColumnName(), tables));
            issue.setSuggestion("建议统一同名字段的数据类型，避免跨表JOIN时出现隐式转换");
            issue.setStatus(MetadataQualityIssue.STATUS_OPEN);
            issues.add(issue);
        }
    }

    private void checkCommentConflict(CheckContext ctx, MetadataQualityRule rule,
                                      List<DbColumnMeta> cols, List<MetadataQualityIssue> issues) {
        // 只比较有注释的字段
        List<DbColumnMeta> withComment = cols.stream()
                .filter(c -> StringUtils.hasText(c.getColumnComment()))
                .toList();
        if (withComment.size() < 2) return;

        Map<String, List<DbColumnMeta>> byComment = new HashMap<>();
        for (DbColumnMeta col : withComment) {
            byComment.computeIfAbsent(col.getColumnComment().trim(), k -> new ArrayList<>()).add(col);
        }

        if (byComment.size() > 1) {
            DbColumnMeta first = withComment.get(0);
            String details = withComment.stream()
                    .map(c -> c.getTableName() + "(\"" + c.getColumnComment() + "\")")
                    .collect(Collectors.joining(", "));

            MetadataQualityIssue issue = new MetadataQualityIssue();
            issue.setSnapshotId(ctx.snapshotId());
            issue.setDatasourceId(ctx.datasourceId());
            issue.setRuleId(rule.getId());
            issue.setDimension(rule.getDimension());
            issue.setSeverity(rule.getSeverity());
            issue.setTableName(first.getTableName());
            issue.setColumnName(first.getColumnName());
            issue.setIssueDescription(String.format("字段 %s 在多表中注释不一致: %s",
                    first.getColumnName(), details));
            issue.setSuggestion("建议统一同名字段的注释描述，确保语义一致");
            issue.setStatus(MetadataQualityIssue.STATUS_OPEN);
            issues.add(issue);
        }
    }
}
