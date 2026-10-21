package com.dataocean.module.governance.checker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.util.JdbcUrlBuilder;
import com.dataocean.common.util.SqlIdentifierValidator;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.entity.po.DatasourceSecret;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.TableRelation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据级质量规则执行器。
 * <p>
 * 连接用户数据源，执行 SQL 查询验证数据级质量规则：
 * <ul>
 *   <li>DATA_NULL_RATE_HIGH：检测列空值率是否超过阈值</li>
 *   <li>DATA_UNIQUE_VIOLATION：检测应唯一列是否出现重复值</li>
 *   <li>DATA_FK_ORPHAN：检测外键引用的目标记录是否存在</li>
 *   <li>DATA_STALE_TABLE：检测表数据是否超过配置天数未更新</li>
 * </ul>
 * </p>
 *
 * @author DataOcean
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataQualityChecker implements QualityChecker {

    private final DatasourceMapper datasourceMapper;
    private final DatasourceSecretMapper datasourceSecretMapper;
    private final DatasourceSecretService datasourceSecretService;

    /** SQL 执行超时时间（秒） */
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    /** 单表最大采样行数（避免全表扫描） */
    private static final int MAX_SAMPLE_ROWS = 100000;

    @Override
    public String getDimension() {
        return "DATA";
    }

    /**
     * 数据级规则落在四个既有质量维度中，不能以独立的 DATA 维度参与白名单筛选。
     */
    @Override
    public Set<String> getSupportedDimensions() {
        return Set.of(
                MetadataQualityRule.DIM_COMPLETENESS,
                MetadataQualityRule.DIM_ACCURACY,
                MetadataQualityRule.DIM_CONSISTENCY,
                MetadataQualityRule.DIM_TIMELINESS);
    }

    @Override
    public List<MetadataQualityIssue> check(CheckContext context) {
        List<MetadataQualityIssue> issues = new ArrayList<>();

        // 提取 DATA 类型的规则
        Map<String, MetadataQualityRule> ruleMap = context.rules().stream()
                .filter(r -> MetadataQualityRule.CHECK_TYPE_DATA.equals(r.getCheckType())
                        && getSupportedDimensions().contains(r.getDimension())
                        && Integer.valueOf(1).equals(r.getEnabled()))
                .collect(Collectors.toMap(MetadataQualityRule::getRuleCode, r -> r));

        if (ruleMap.isEmpty()) {
            return issues;
        }

        // 获取数据源连接信息
        Connection conn = getConnection(context.datasourceId());

        try {
            // 执行各规则检查
            MetadataQualityRule nullRateRule = ruleMap.get("DATA_NULL_RATE_HIGH");
            if (nullRateRule != null) {
                issues.addAll(checkNullRate(conn, context, nullRateRule));
            }

            MetadataQualityRule uniqueRule = ruleMap.get("DATA_UNIQUE_VIOLATION");
            if (uniqueRule != null) {
                issues.addAll(checkUniqueness(conn, context, uniqueRule));
            }

            MetadataQualityRule fkOrphanRule = ruleMap.get("DATA_FK_ORPHAN");
            if (fkOrphanRule != null) {
                issues.addAll(checkForeignKeyOrphans(conn, context, fkOrphanRule));
            }

            MetadataQualityRule staleRule = ruleMap.get("DATA_STALE_TABLE");
            if (staleRule != null) {
                issues.addAll(checkDataStaleness(conn, context, staleRule));
            }

        } finally {
            closeQuietly(conn);
        }

        log.info("DATA 级质量检查完成 datasourceId={} issues={}", context.datasourceId(), issues.size());
        return issues;
    }

    /**
     * 检查列空值率。
     * 对每列执行 SELECT COUNT(*) / SUM(CASE WHEN col IS NULL THEN 1 ELSE 0 END) 计算空值率。
     */
    private List<MetadataQualityIssue> checkNullRate(Connection conn, CheckContext context,
                                                      MetadataQualityRule rule) {
        List<MetadataQualityIssue> issues = new ArrayList<>();
        BigDecimal threshold = QualityRuleParameterReader.decimal(rule, "threshold",
                rule.getThreshold() != null ? rule.getThreshold() : new BigDecimal("0.5"));

        for (DbColumnMeta column : context.columns()) {
            if (!usableColumn(context, column)) continue;
            try {
                String escapedColumn = escapeIdentifier(column.getColumnName());
                String escapedTable = escapeIdentifier(column.getTableName());
                String sql = String.format(
                        "SELECT COUNT(*) AS total, SUM(CASE WHEN sampled.`sample_value` IS NULL THEN 1 ELSE 0 END) AS nulls "
                                + "FROM (SELECT `%s` AS `sample_value` FROM `%s` LIMIT %d) AS sampled",
                        escapedColumn, escapedTable, MAX_SAMPLE_ROWS);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = query(stmt, sql)) {
                    if (rs.next()) {
                        long total = rs.getLong("total");
                        long nulls = rs.getLong("nulls");
                        if (total > 0) {
                            BigDecimal nullRate = new BigDecimal(nulls).divide(new BigDecimal(total), 4, java.math.RoundingMode.HALF_UP);
                            if (nullRate.compareTo(threshold) > 0) {
                                issues.add(buildIssue(context, rule, column.getTableName(), column.getColumnName(),
                                        String.format("字段 %s.%s 空值率 %.1f%% 超过阈值 %.1f%%",
                                                column.getTableName(), column.getColumnName(),
                                                nullRate.multiply(new BigDecimal("100")).doubleValue(),
                                                threshold.multiply(new BigDecimal("100")).doubleValue()),
                                        "建议检查数据采集流程，补充缺失数据或调整字段可空性"));
                            }
                        }
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                log.error("空值率规则执行失败 datasourceId={} table={} column={}",
                        context.datasourceId(), column.getTableName(), column.getColumnName(), e);
                throw new BusinessException(500, "空值率检查未完成");
            }
        }
        return issues;
    }

    /**
     * 检查唯一性违规。
     * 对有唯一约束的列执行 SELECT col, COUNT(*) GROUP BY col HAVING COUNT(*) > 1。
     */
    private List<MetadataQualityIssue> checkUniqueness(Connection conn, CheckContext context,
                                                        MetadataQualityRule rule) {
        List<MetadataQualityIssue> issues = new ArrayList<>();

        boolean primaryKeyOnly = QualityRuleParameterReader.bool(rule, "primaryKeyOnly", true);
        for (DbColumnMeta column : context.columns()) {
            if (!usableColumn(context, column)) continue;
            if (primaryKeyOnly && (column.getIsPrimaryKey() == null || column.getIsPrimaryKey() != 1)) {
                continue;
            }

            try {
                String escapedColumn = escapeIdentifier(column.getColumnName());
                String escapedTable = escapeIdentifier(column.getTableName());
                String sql = String.format(
                        "SELECT sampled.`unique_value`, COUNT(*) AS cnt FROM "
                                + "(SELECT `%s` AS `unique_value` FROM `%s` LIMIT %d) AS sampled "
                                + "GROUP BY sampled.`unique_value` HAVING COUNT(*) > 1 LIMIT 5",
                        escapedColumn, escapedTable, MAX_SAMPLE_ROWS);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = query(stmt, sql)) {
                    if (rs.next()) {
                        issues.add(buildIssue(context, rule, column.getTableName(), column.getColumnName(),
                                String.format("字段 %s.%s 存在重复值（应唯一）",
                                        column.getTableName(), column.getColumnName()),
                                "建议检查数据录入流程，清理重复数据或添加唯一约束"));
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                log.error("唯一性规则执行失败 datasourceId={} table={} column={}",
                        context.datasourceId(), column.getTableName(), column.getColumnName(), e);
                throw new BusinessException(500, "唯一性检查未完成");
            }
        }
        return issues;
    }

    /**
     * 检查外键孤儿记录。
     * 对每个外键关系执行 SELECT COUNT(*) FROM child WHERE fk NOT IN (SELECT pk FROM parent)。
     */
    private List<MetadataQualityIssue> checkForeignKeyOrphans(Connection conn, CheckContext context,
                                                               MetadataQualityRule rule) {
        List<MetadataQualityIssue> issues = new ArrayList<>();

        for (TableRelation relation : context.relations()) {
            if (!usableRelation(context, relation)) continue;
            String childTable = relation.getSourceTable();
            String childColumn = relation.getSourceColumn();
            String parentTable = relation.getTargetTable();
            String parentColumn = relation.getTargetColumn();

            if (childTable == null || childColumn == null || parentTable == null || parentColumn == null) {
                continue;
            }

            try {
                String escapedChildTable = escapeIdentifier(childTable);
                String escapedChildColumn = escapeIdentifier(childColumn);
                String escapedParentTable = escapeIdentifier(parentTable);
                String escapedParentColumn = escapeIdentifier(parentColumn);
                String sql = String.format(
                        "SELECT COUNT(*) AS orphans FROM "
                                + "(SELECT `%s` AS `child_value` FROM `%s` WHERE `%s` IS NOT NULL LIMIT %d) AS c "
                                + "WHERE NOT EXISTS (SELECT 1 FROM `%s` AS p WHERE p.`%s` = c.`child_value` LIMIT 1) LIMIT 1",
                        escapedChildColumn, escapedChildTable, escapedChildColumn, MAX_SAMPLE_ROWS,
                        escapedParentTable, escapedParentColumn);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = query(stmt, sql)) {
                    if (rs.next()) {
                        long orphans = rs.getLong("orphans");
                        if (orphans > 0) {
                            issues.add(buildIssue(context, rule, childTable, childColumn,
                                    String.format("外键 %s.%s → %s.%s 存在 %d 条孤儿记录",
                                            childTable, childColumn, parentTable, parentColumn, orphans),
                                    "建议修复孤儿记录，补充关联数据或清理无效外键"));
                        }
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                log.error("外键完整性规则执行失败 datasourceId={} table={} column={}",
                        context.datasourceId(), childTable, childColumn, e);
                throw new BusinessException(500, "外键完整性检查未完成");
            }
        }
        return issues;
    }

    /**
     * 检查数据陈旧性。
     * 对有 update_time/updated_at 列的表，检查最新更新时间是否超过阈值天数。
     */
    private List<MetadataQualityIssue> checkDataStaleness(Connection conn, CheckContext context,
                                                           MetadataQualityRule rule) {
        List<MetadataQualityIssue> issues = new ArrayList<>();
        int thresholdDays = QualityRuleParameterReader.integer(rule, "staleDays",
                rule.getThreshold() != null ? rule.getThreshold().intValue() : 30);

        for (DbTableMeta table : context.tables()) {
            // 找到该表的时间列
            String timeColumn = QualityStaleTimeFieldSelector.select(table, context.columns());
            if (timeColumn == null) {
                continue;
            }

            try {
                String escapedColumn = escapeIdentifier(timeColumn);
                String escapedTable = escapeIdentifier(table.getTableName());
                String sql = String.format(
                        "SELECT MAX(sampled.`time_value`) AS last_update FROM "
                                + "(SELECT `%s` AS `time_value` FROM `%s` ORDER BY `%s` DESC LIMIT %d) AS sampled LIMIT 1",
                        escapedColumn, escapedTable, escapedColumn, MAX_SAMPLE_ROWS);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = query(stmt, sql)) {
                    if (rs.next()) {
                        Timestamp lastUpdate = rs.getTimestamp("last_update");
                        if (lastUpdate != null) {
                            long daysSinceUpdate = Duration.between(
                                    lastUpdate.toLocalDateTime(), LocalDateTime.now()).toDays();
                            if (daysSinceUpdate > thresholdDays) {
                                issues.add(buildIssue(context, rule, table.getTableName(), null,
                                        String.format("表 %s 数据已 %d 天未更新（阈值 %d 天）",
                                                table.getTableName(), daysSinceUpdate, thresholdDays),
                                        "建议检查数据同步流程，确保数据及时更新"));
                            }
                        }
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                log.error("数据时效规则执行失败 datasourceId={} table={}",
                        context.datasourceId(), table.getTableName(), e);
                throw new BusinessException(500, "数据时效检查未完成");
            }
        }
        return issues;
    }

    private boolean usableColumn(CheckContext context, DbColumnMeta column) {
        if (QualityStaleTimeFieldSelector.restricted(column.getGovernanceStatus())) return false;
        DbTableMeta table = findTable(context.tables(), column.getTableName());
        return table != null && !QualityStaleTimeFieldSelector.restricted(table.getGovernanceStatus());
    }

    private boolean usableRelation(CheckContext context, TableRelation relation) {
        if (relation.getSourceTable() == null || relation.getSourceColumn() == null
                || relation.getTargetTable() == null || relation.getTargetColumn() == null) return false;
        DbTableMeta sourceTable = findTable(context.tables(), relation.getSourceTable());
        DbTableMeta targetTable = findTable(context.tables(), relation.getTargetTable());
        DbColumnMeta sourceColumn = findColumn(context.columns(), relation.getSourceTable(), relation.getSourceColumn());
        DbColumnMeta targetColumn = findColumn(context.columns(), relation.getTargetTable(), relation.getTargetColumn());
        return sourceTable != null && targetTable != null && sourceColumn != null && targetColumn != null
                && !QualityStaleTimeFieldSelector.restricted(sourceTable.getGovernanceStatus())
                && !QualityStaleTimeFieldSelector.restricted(targetTable.getGovernanceStatus())
                && !QualityStaleTimeFieldSelector.restricted(sourceColumn.getGovernanceStatus())
                && !QualityStaleTimeFieldSelector.restricted(targetColumn.getGovernanceStatus());
    }

    private DbTableMeta findTable(List<DbTableMeta> tables, String tableName) {
        return tables.stream().filter(table -> tableName.equals(table.getTableName())).findFirst().orElse(null);
    }

    private DbColumnMeta findColumn(List<DbColumnMeta> columns, String tableName, String columnName) {
        return columns.stream().filter(column -> tableName.equals(column.getTableName())
                && columnName.equals(column.getColumnName())).findFirst().orElse(null);
    }

    /**
     * 获取数据源 JDBC 连接。
     * 解密密码并创建只读连接，设置 30 秒查询超时。
     */
    private Connection getConnection(Long datasourceId) {
        Datasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new BusinessException(400, "数据源不存在，无法执行数据质量检查");
        }
        if (!Integer.valueOf(Datasource.STATUS_ENABLED).equals(ds.getStatus())) {
            throw new BusinessException(400, "数据源未启用，无法执行数据质量检查");
        }

        DatasourceSecret secret = datasourceSecretMapper.selectOne(
                new LambdaQueryWrapper<DatasourceSecret>()
                        .eq(DatasourceSecret::getDatasourceId, datasourceId));
        if (secret == null) {
            throw new BusinessException(400, "数据源未配置连接凭证，无法执行数据质量检查");
        }

        String password = decryptPassword(secret.getEncryptedPassword());
        if (password == null) {
            log.error("数据源密码解密失败，拒绝连接 datasourceId={}", datasourceId);
            throw new BusinessException(400, "数据源密码解密失败，请重新保存连接配置");
        }

        try {
            String url = JdbcUrlBuilder.metadataMysqlUrl(
                    ds.getHost(), ds.getPort(), ds.getDatabaseName(), "utf8mb4");
            Properties props = new Properties();
            props.setProperty("user", secret.getUsername());
            props.setProperty("password", password);
            props.setProperty("connectTimeout", "10000");
            props.setProperty("socketTimeout", String.valueOf(QUERY_TIMEOUT_SECONDS * 1000));

            Connection conn = DriverManager.getConnection(url, props);
            conn.setReadOnly(true);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            return conn;
        } catch (SQLException e) {
            log.error("数据源连接失败 datasourceId={} sqlState={}", datasourceId, e.getSQLState(), e);
            throw new BusinessException(400, "数据源连接失败，请检查连接配置");
        }
    }

    /**
     * 校验 SQL 标识符，防止将异常元数据直接拼接进 SQL。
     * <p>
     * 标识符来自 information_schema 元数据采集，仍需使用白名单约束。
     * </p>
     *
     * @param identifier 表名或字段名
     * @return 校验后的标识符（用于嵌入反引号包裹的 SQL）
     */
    private static String escapeIdentifier(String identifier) {
        return SqlIdentifierValidator.validate(identifier);
    }

    private static ResultSet query(Statement statement, String sql) throws SQLException {
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        statement.setMaxRows(5);
        return statement.executeQuery(sql);
    }

    /**
     * 解密数据源密码，复用 DatasourceSecretService 统一解密逻辑。
     */
    private String decryptPassword(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        try {
            return datasourceSecretService.decrypt(encrypted);
        } catch (Exception e) {
            log.error("数据源密码解密失败，拒绝连接: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建质量问题记录
     */
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
        resolveAndSetColumnMetaId(issue, ctx.columns());  // Phase 1 #6
        return issue;
    }

    /**
     * 安静关闭数据库连接
     */
    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                log.debug("关闭连接异常: {}", e.getMessage());
            }
        }
    }
}
