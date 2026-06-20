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
 * 完整性校验器。
 * <p>
 * 从三个维度检测元数据完整性：
 * <ul>
 *   <li>表注释缺失（COMP_TABLE_COMMENT_MISSING）：检查表是否有业务含义说明</li>
 *   <li>字段注释缺失（COMP_COLUMN_COMMENT_MISSING）：检查字段是否有取值说明</li>
 *   <li>主键缺失（COMP_PRIMARY_KEY_MISSING）：检查表是否有主键定义</li>
 * </ul>
 * </p>
 * <p>
 * 校验策略：
 * <ul>
 *   <li>遍历所有表/列，检查对应属性是否为空</li>
 *   <li>主键检测通过收集所有有主键的表名，然后检查哪些表不在集合中</li>
 *   <li>每个问题对应一个规则编码，便于后续统计和扣分</li>
 * </ul>
 * </p>
 *
 * @author DataOcean
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
     * <p>
     * 校验流程：
     * 1. 从规则列表中提取完整性维度的规则，构建 ruleCode -> rule 映射
     * 2. 分别检测表注释缺失、字段注释缺失、主键缺失
     * 3. 每个检测项独立执行，发现问题则构建问题记录
     * </p>
     */
    @Override
    public List<MetadataQualityIssue> check(CheckContext context) {
        List<MetadataQualityIssue> issues = new ArrayList<>();

        // 第一步：从规则列表中提取完整性维度的规则，构建 ruleCode -> rule 映射
        // 使用 Stream 的 filter 过滤出当前维度且启用的规则，collect 收集成 Map
        Map<String, MetadataQualityRule> ruleMap = context.rules().stream()
                .filter(r -> getDimension().equals(r.getDimension()) && r.getEnabled() == 1)  // 过滤：维度匹配且启用
                .collect(Collectors.toMap(MetadataQualityRule::getRuleCode, r -> r));         // 收集成 Map<ruleCode, rule>

        // 获取三个校验规则（如果规则不存在则跳过对应检测）
        MetadataQualityRule tableCommentRule = ruleMap.get("COMP_TABLE_COMMENT_MISSING");   // 表注释缺失规则
        MetadataQualityRule columnCommentRule = ruleMap.get("COMP_COLUMN_COMMENT_MISSING"); // 字段注释缺失规则
        MetadataQualityRule pkRule = ruleMap.get("COMP_PRIMARY_KEY_MISSING");               // 主键缺失规则

        // 第二步：检测表注释缺失
        // 遍历所有表，检查 tableComment 是否为空
        if (tableCommentRule != null) {
            for (DbTableMeta table : context.tables()) {
                if (!StringUtils.hasText(table.getTableComment())) {
                    issues.add(buildIssue(context, tableCommentRule, table.getTableName(), null,
                            String.format("表 %s 缺少表注释", table.getTableName()),
                            "建议补充表注释，描述该表的业务含义"));
                }
            }
        }

        // 第三步：检测字段注释缺失
        // 遍历所有列，检查 columnComment 是否为空
        if (columnCommentRule != null) {
            for (DbColumnMeta col : context.columns()) {
                if (!StringUtils.hasText(col.getColumnComment())) {
                    issues.add(buildIssue(context, columnCommentRule, col.getTableName(), col.getColumnName(),
                            String.format("字段 %s.%s 缺少字段注释", col.getTableName(), col.getColumnName()),
                            "建议补充字段注释，说明该字段的取值含义"));
                }
            }
        }

        // 第四步：检测主键缺失
        // 首先收集所有有主键的表名集合，然后检查哪些表不在集合中
        if (pkRule != null) {
            // 使用 Stream 收集所有有主键的表名
            Set<String> tablesWithPk = context.columns().stream()
                    .filter(c -> c.getIsPrimaryKey() != null && c.getIsPrimaryKey() == 1)  // 过滤出主键字段
                    .map(DbColumnMeta::getTableName)                                        // 提取表名
                    .collect(Collectors.toSet());                                            // 收集成 Set（自动去重）

            // 检查哪些表没有主键
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
