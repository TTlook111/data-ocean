package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.TableRelation;
import com.dataocean.module.governance.checker.QualityRuleParameterReader;
import com.dataocean.module.governance.checker.QualityStaleTimeFieldSelector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 将同模板规则按 GLOBAL < DATASOURCE < TABLE < COLUMN 解析为每个目标唯一的处理器实例。 */
public class QualityRuleExecutionPlanner {
    public record Execution(MetadataQualityRule rule,
                            QualityRuleTemplateRegistry.Definition template,
                            List<DbTableMeta> tables,
                            List<DbColumnMeta> columns,
                            List<TableRelation> relations,
                            int targetCount,
                            int checkedTargetCount,
                            String status,
                            String reason,
                            boolean blocking) {}

    private final QualityRuleTemplateRegistry registry;

    public QualityRuleExecutionPlanner(QualityRuleTemplateRegistry registry) {
        this.registry = registry;
    }

    public List<Execution> plan(Long datasourceId,
                                List<MetadataQualityRule> rules,
                                List<DbTableMeta> allTables,
                                List<DbColumnMeta> allColumns,
                                List<TableRelation> allRelations,
                                List<DbTableMeta> selectedTables,
                                List<DbColumnMeta> selectedColumns,
                                List<TableRelation> selectedRelations,
                                Set<String> requestedTableNames) {
        Map<String, List<MutableExecution>> byTemplate = new LinkedHashMap<>();
        for (MetadataQualityRule rule : rules) {
            String templateCode = templateCode(rule);
            QualityRuleTemplateRegistry.Definition definition = registry.find(templateCode);
            MutableExecution execution = new MutableExecution(rule, definition);
            if (definition == null) {
                execution.fail("该规则未映射到已注册检查模板");
            } else if (!definition.getDimension().equals(rule.getDimension())
                    || !definition.getCheckType().equals(rule.getCheckType())
                    || !definition.getTargetType().equals(rule.getCheckTarget())) {
                execution.fail("规则维度或目标类型与已注册模板不一致");
            } else if (!definition.getAllowedScopes().contains(scope(rule))) {
                execution.fail("规则作用范围不受该检查模板支持");
            }
            byTemplate.computeIfAbsent(templateCode, ignored -> new ArrayList<>()).add(execution);
        }

        for (List<MutableExecution> group : byTemplate.values()) {
            List<MutableExecution> candidates = group.stream().filter(e -> !"FAILURE".equals(e.status)).toList();
            if (candidates.isEmpty()) continue;
            QualityRuleTemplateRegistry.Definition template = candidates.get(0).template;
            if (isAggregate(template)) {
                int potentialTargets = targetCount(template, selectedTables, selectedColumns, selectedRelations);
                List<TableRelation> effectiveRelations = selectedRelations;
                if ("DATA_FK_ORPHAN".equals(template.getCode())) {
                    effectiveRelations = selectedRelations.stream()
                            .filter(relation -> relationUsable(relation, selectedTables, selectedColumns)).toList();
                }
                planAggregate(datasourceId, template, candidates, selectedTables, selectedColumns,
                        effectiveRelations, potentialTargets);
            } else if (MetadataQualityRule.TARGET_TABLE.equals(template.getTargetType())) {
                planTables(datasourceId, template, candidates, allTables, allColumns,
                        selectedTables, selectedColumns, requestedTableNames);
            } else if (MetadataQualityRule.TARGET_COLUMN.equals(template.getTargetType())) {
                planColumns(datasourceId, template, candidates, allTables, allColumns,
                        selectedTables, selectedColumns, requestedTableNames);
            } else if (MetadataQualityRule.TARGET_RELATION.equals(template.getTargetType())) {
                planAggregate(datasourceId, template, candidates, selectedTables, selectedColumns, selectedRelations,
                        targetCount(template, selectedTables, selectedColumns, selectedRelations));
            } else {
                candidates.forEach(e -> e.fail("模板目标类型没有执行计划"));
            }
        }

        return byTemplate.values().stream().flatMap(List::stream).map(MutableExecution::freeze).toList();
    }

    private void planAggregate(Long datasourceId, QualityRuleTemplateRegistry.Definition template,
                               List<MutableExecution> candidates, List<DbTableMeta> tables,
                               List<DbColumnMeta> columns, List<TableRelation> relations,
                               int potentialTargetCount) {
        candidates.forEach(execution -> execution.targetCount = potentialTargetCount);
        MutableExecution winner = uniqueWinner(candidates, template.getCode());
        if (winner == null) return;
        int applicableCount = targetCount(template, tables, columns, relations);
        if (applicableCount == 0) {
            winner.notApplicable(potentialTargetCount > 0
                    ? "目标关系涉及废弃或阻断对象，已跳过数据读取"
                    : "当前检查范围没有适用的目标对象", true);
            return;
        }
        winner.tables.addAll(tables);
        winner.columns.addAll(columns);
        winner.relations.addAll(relations);
        winner.checkedTargetCount = applicableCount;
        winner.blocking = applicableCount < potentialTargetCount;
        if (winner.blocking) winner.reason = "部分目标涉及废弃或阻断对象，已跳过数据读取";
        winner.status = "READY";
    }

    private void planTables(Long datasourceId, QualityRuleTemplateRegistry.Definition template,
                            List<MutableExecution> candidates, List<DbTableMeta> allTables,
                            List<DbColumnMeta> allColumns, List<DbTableMeta> selectedTables,
                            List<DbColumnMeta> selectedColumns, Set<String> requestedNames) {
        Map<String, DbTableMeta> selectedByName = selectedTables.stream()
                .collect(Collectors.toMap(DbTableMeta::getTableName, t -> t, (a, b) -> a, LinkedHashMap::new));
        for (DbTableMeta table : selectedByName.values()) {
            List<MutableExecution> matching = candidates.stream().filter(e -> matches(e.rule, datasourceId,
                    table.getTableName(), null)).toList();
            if (matching.isEmpty()) continue;
            MutableExecution winner = uniqueWinner(matching, template.getCode());
            matching.forEach(e -> e.targetCount++);
            if (winner != null) {
                if (isDataTemplate(template) && restrictedStatus(table.getGovernanceStatus())) {
                    winner.restrictedTargetCount++;
                } else if (tableSupported(template, table, selectedColumns)) {
                    winner.tables.add(table);
                    winner.checkedTargetCount++;
                } else {
                    winner.unsupportedTargetCount++;
                    if ("DATA_STALE_TABLE".equals(template.getCode())) {
                        winner.unsupportedReason = "没有治理状态允许的时间字段，未执行时效读取";
                    }
                }
            }
        }
        finishTargetPlans(candidates, allTables.stream().map(DbTableMeta::getTableName).collect(Collectors.toSet()),
                requestedNames, template.getTargetType());
    }

    private void planColumns(Long datasourceId, QualityRuleTemplateRegistry.Definition template,
                             List<MutableExecution> candidates, List<DbTableMeta> allTables,
                             List<DbColumnMeta> allColumns, List<DbTableMeta> selectedTables,
                             List<DbColumnMeta> selectedColumns, Set<String> requestedNames) {
        Set<String> selectedTableNames = selectedTables.stream().map(DbTableMeta::getTableName).collect(Collectors.toSet());
        Map<String, DbTableMeta> selectedTableByName = selectedTables.stream()
                .collect(Collectors.toMap(DbTableMeta::getTableName, table -> table, (a, b) -> a));
        List<DbColumnMeta> candidatesInScope = selectedColumns.stream()
                .filter(c -> selectedTableNames.contains(c.getTableName())).toList();
        for (DbColumnMeta column : candidatesInScope) {
            List<MutableExecution> matching = candidates.stream()
                    .filter(e -> matches(e.rule, datasourceId,
                    column.getTableName(), column.getColumnName())).toList();
            if (matching.isEmpty()) continue;
            MutableExecution winner = uniqueWinner(matching, template.getCode());
            matching.forEach(e -> e.targetCount++);
            if (winner != null) {
                DbTableMeta parentTable = selectedTableByName.get(column.getTableName());
                if (isDataTemplate(template) && (restrictedStatus(parentTable.getGovernanceStatus())
                        || restrictedStatus(column.getGovernanceStatus()))) {
                    winner.restrictedTargetCount++;
                } else if (targetSupported(template.getCode(), winner.rule, column)) {
                    winner.columns.add(column);
                    winner.checkedTargetCount++;
                    winner.tables.add(parentTable);
                } else {
                    winner.unsupportedTargetCount++;
                }
            }
        }
        Set<String> allColumnKeys = allColumns.stream().map(c -> c.getTableName() + "." + c.getColumnName())
                .collect(Collectors.toSet());
        finishColumnPlans(candidates, allTables.stream().map(DbTableMeta::getTableName).collect(Collectors.toSet()),
                allColumnKeys, requestedNames);
    }

    private void finishTargetPlans(List<MutableExecution> candidates, Set<String> allTableNames,
                                   Set<String> requestedNames, String targetType) {
        for (MutableExecution execution : candidates) {
            if ("FAILURE".equals(execution.status)) continue;
            if (!execution.tables.isEmpty()) {
                execution.status = "READY";
                if (execution.restrictedTargetCount > 0) {
                    execution.blocking = true;
                    execution.reason = "部分目标为废弃或阻断状态，未执行数据读取";
                } else if (execution.unsupportedTargetCount > 0) {
                    execution.blocking = true;
                    execution.reason = execution.unsupportedReason == null
                            ? "部分目标因治理状态或检查条件不适用，已跳过"
                            : "部分目标没有治理状态允许的时间字段，已跳过时效读取";
                } else if (execution.overriddenCount > 0) execution.reason = "部分目标由更具体范围覆盖";
                continue;
            }
            if (execution.restrictedTargetCount > 0) {
                execution.notApplicable("目标为废弃或阻断状态，未执行数据读取", true);
                continue;
            }
            if (execution.unsupportedTargetCount > 0) {
                execution.notApplicable(execution.unsupportedReason == null
                        ? "目标对象不符合安全或模板适用条件"
                        : execution.unsupportedReason, true);
                continue;
            }
            if (execution.targetCount > 0) {
                execution.notApplicable("所有目标均由更具体范围的规则覆盖", false);
                continue;
            }
            String table = execution.rule.getTargetTableName();
            if ("TABLE".equals(scope(execution.rule)) && table != null && !allTableNames.contains(table)) {
                execution.notApplicable("配置的表在当前快照中不存在", true);
            } else if ("TABLE".equals(scope(execution.rule)) && requestedNames != null && !requestedNames.contains(table)) {
                execution.notExecuted("当前局部校验范围未包含该规则目标");
            } else {
                execution.notApplicable("当前检查范围没有适用的表对象", true);
            }
        }
    }

    private void finishColumnPlans(List<MutableExecution> candidates, Set<String> allTableNames,
                                   Set<String> allColumnKeys, Set<String> requestedNames) {
        for (MutableExecution execution : candidates) {
            if ("FAILURE".equals(execution.status)) continue;
            if (!execution.columns.isEmpty()) {
                execution.status = "READY";
                if (execution.restrictedTargetCount > 0) {
                    execution.blocking = true;
                    execution.reason = "部分目标为废弃或阻断状态，未执行数据读取";
                } else if (execution.unsupportedTargetCount > 0) {
                    execution.blocking = true;
                    execution.reason = "部分目标因治理状态或检查条件不适用，已跳过";
                } else if (execution.overriddenCount > 0) execution.reason = "部分目标由更具体范围覆盖";
                continue;
            }
            if (execution.restrictedTargetCount > 0) {
                execution.notApplicable("目标为废弃或阻断状态，未执行数据读取", true);
                continue;
            }
            if (execution.unsupportedTargetCount > 0) {
                execution.notApplicable("目标对象不符合安全或模板适用条件", true);
                continue;
            }
            if (execution.targetCount > 0) {
                execution.notApplicable("所有目标均由更具体范围的规则覆盖", false);
                continue;
            }
            String table = execution.rule.getTargetTableName();
            String column = execution.rule.getTargetColumnName();
            String scope = scope(execution.rule);
            if ("TABLE".equals(scope) && table != null && !allTableNames.contains(table)) {
                execution.notApplicable("配置的表在当前快照中不存在", true);
            } else if ("COLUMN".equals(scope) && table != null && column != null
                    && !allColumnKeys.contains(table + "." + column)) {
                execution.notApplicable("配置的字段在当前快照中不存在", true);
            } else if (requestedNames != null && table != null && !requestedNames.contains(table)) {
                execution.notExecuted("当前局部校验范围未包含该规则目标");
            } else if ("DATA_UNIQUE_VIOLATION".equals(templateCode(execution.rule))) {
                execution.notApplicable("所选字段没有唯一约束或主键依据", true);
            } else if ("DATA_STALE_TABLE".equals(templateCode(execution.rule))) {
                execution.notApplicable("当前目标没有可用于判断更新时效的时间字段", true);
            } else {
                execution.notApplicable("当前检查范围没有适用的字段对象", true);
            }
        }
    }

    private MutableExecution uniqueWinner(List<MutableExecution> matching, String templateCode) {
        if (matching.isEmpty()) return null;
        int priority = matching.stream().mapToInt(e -> specificity(scope(e.rule))).max().orElse(-1);
        List<MutableExecution> highest = matching.stream().filter(e -> specificity(scope(e.rule)) == priority).toList();
        if (highest.size() > 1) {
            for (MutableExecution execution : highest) execution.fail("同一级范围存在冲突的启用规则: " + templateCode);
            for (MutableExecution execution : matching) {
                if (!highest.contains(execution) && !"FAILURE".equals(execution.status)) execution.overriddenCount++;
            }
            return null;
        }
        MutableExecution winner = highest.get(0);
        for (MutableExecution execution : matching) {
            if (execution != winner && !"FAILURE".equals(execution.status)) execution.overriddenCount++;
        }
        return winner;
    }

    private boolean matches(MetadataQualityRule rule, Long datasourceId, String table, String column) {
        String scope = scope(rule);
        if (QualityRuleTemplateRegistry.SCOPE_GLOBAL.equals(scope)) return true;
        if (!java.util.Objects.equals(datasourceId, rule.getDatasourceId())) return false;
        if (QualityRuleTemplateRegistry.SCOPE_DATASOURCE.equals(scope)) return true;
        if (!java.util.Objects.equals(table, rule.getTargetTableName())) return false;
        if (QualityRuleTemplateRegistry.SCOPE_TABLE.equals(scope)) return true;
        return QualityRuleTemplateRegistry.SCOPE_COLUMN.equals(scope)
                && java.util.Objects.equals(column, rule.getTargetColumnName());
    }

    private boolean targetSupported(String templateCode, MetadataQualityRule rule, DbColumnMeta column) {
        if ("DATA_UNIQUE_VIOLATION".equals(templateCode)) {
            return !QualityRuleParameterReader.bool(rule, "primaryKeyOnly", true)
                    || column.getIsPrimaryKey() != null && column.getIsPrimaryKey() == 1;
        }
        return true;
    }

    private boolean tableSupported(QualityRuleTemplateRegistry.Definition template,
                                   DbTableMeta table, List<DbColumnMeta> columns) {
        if (!"DATA_STALE_TABLE".equals(template.getCode())) return true;
        return QualityStaleTimeFieldSelector.select(table, columns) != null;
    }

    private boolean relationUsable(TableRelation relation, List<DbTableMeta> tables, List<DbColumnMeta> columns) {
        DbTableMeta source = tables.stream().filter(table -> table.getTableName().equals(relation.getSourceTable()))
                .findFirst().orElse(null);
        DbTableMeta target = tables.stream().filter(table -> table.getTableName().equals(relation.getTargetTable()))
                .findFirst().orElse(null);
        if (source == null || target == null || restrictedStatus(source.getGovernanceStatus())
                || restrictedStatus(target.getGovernanceStatus())) return false;
        DbColumnMeta sourceColumn = columns.stream().filter(column ->
                column.getTableName().equals(relation.getSourceTable())
                        && column.getColumnName().equals(relation.getSourceColumn())).findFirst().orElse(null);
        DbColumnMeta targetColumn = columns.stream().filter(column ->
                column.getTableName().equals(relation.getTargetTable())
                        && column.getColumnName().equals(relation.getTargetColumn())).findFirst().orElse(null);
        return sourceColumn != null && targetColumn != null
                && !restrictedStatus(sourceColumn.getGovernanceStatus())
                && !restrictedStatus(targetColumn.getGovernanceStatus());
    }

    private boolean isDataTemplate(QualityRuleTemplateRegistry.Definition template) {
        return MetadataQualityRule.CHECK_TYPE_DATA.equals(template.getCheckType());
    }

    private boolean restrictedStatus(String status) {
        return "DEPRECATED".equals(status) || "BLOCKED".equals(status);
    }

    private boolean isAggregate(QualityRuleTemplateRegistry.Definition definition) {
        return !definition.getAllowedScopes().contains(QualityRuleTemplateRegistry.SCOPE_TABLE)
                && !definition.getAllowedScopes().contains(QualityRuleTemplateRegistry.SCOPE_COLUMN);
    }

    private int targetCount(QualityRuleTemplateRegistry.Definition definition, List<DbTableMeta> tables,
                            List<DbColumnMeta> columns, List<TableRelation> relations) {
        if (MetadataQualityRule.TARGET_TABLE.equals(definition.getTargetType())) {
            return "TIME_SNAPSHOT_EXPIRED".equals(definition.getCode()) ? (tables.isEmpty() ? 0 : 1) : tables.size();
        }
        if (MetadataQualityRule.TARGET_COLUMN.equals(definition.getTargetType())) return columns.size();
        if (MetadataQualityRule.TARGET_RELATION.equals(definition.getTargetType())) return relations.size();
        return 0;
    }

    private String templateCode(MetadataQualityRule rule) {
        return rule.getTemplateCode() == null || rule.getTemplateCode().isBlank()
                ? rule.getRuleCode() : rule.getTemplateCode();
    }

    private String scope(MetadataQualityRule rule) {
        return rule.getScopeType() == null ? QualityRuleTemplateRegistry.SCOPE_GLOBAL : rule.getScopeType();
    }

    private int specificity(String scope) {
        return switch (scope) {
            case QualityRuleTemplateRegistry.SCOPE_COLUMN -> 3;
            case QualityRuleTemplateRegistry.SCOPE_TABLE -> 2;
            case QualityRuleTemplateRegistry.SCOPE_DATASOURCE -> 1;
            default -> 0;
        };
    }

    private static final class MutableExecution {
        private final MetadataQualityRule rule;
        private final QualityRuleTemplateRegistry.Definition template;
        private final List<DbTableMeta> tables = new ArrayList<>();
        private final List<DbColumnMeta> columns = new ArrayList<>();
        private final List<TableRelation> relations = new ArrayList<>();
        private int targetCount;
        private int checkedTargetCount;
        private int unsupportedTargetCount;
        private int restrictedTargetCount;
        private int overriddenCount;
        private String status = "NOT_EXECUTED";
        private String reason;
        private String unsupportedReason;
        private boolean blocking;

        private MutableExecution(MetadataQualityRule rule, QualityRuleTemplateRegistry.Definition template) {
            this.rule = rule;
            this.template = template;
        }

        private void fail(String value) { status = "FAILURE"; reason = value; blocking = true; }
        private void notApplicable(String value, boolean isBlocking) {
            status = "NOT_APPLICABLE"; reason = value; blocking = isBlocking;
        }
        private void notExecuted(String value) { status = "NOT_EXECUTED"; reason = value; }

        private Execution freeze() {
            if ("NOT_EXECUTED".equals(status) && overriddenCount > 0) {
                status = "NOT_APPLICABLE";
                reason = "被更高优先级范围规则覆盖";
            }
            if ("READY".equals(status)) status = "READY";
            return new Execution(rule, template, List.copyOf(tables), List.copyOf(columns),
                    List.copyOf(relations), targetCount, checkedTargetCount, status, reason, blocking);
        }
    }
}
