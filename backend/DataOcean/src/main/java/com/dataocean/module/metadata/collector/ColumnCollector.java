package com.dataocean.module.metadata.collector;

import com.dataocean.module.metadata.entity.DbColumnMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 字段元数据采集器。
 * <p>
 * 通过 JDBC DatabaseMetaData 接口采集指定表的所有字段信息，
 * 包括字段名、类型、注释、是否可空、默认值、是否主键等。
 * </p>
 */
@Slf4j
@Component
public class ColumnCollector {

    /**
     * 采集指定表的所有字段元数据。
     *
     * @param ctx         采集上下文
     * @param tableName   表名
     * @param tableMetaId 表元数据ID
     * @return 字段元数据列表
     * @throws SQLException 数据库访问异常
     */
    public List<DbColumnMeta> collect(CollectorContext ctx, String tableName, Long tableMetaId) throws SQLException {
        List<DbColumnMeta> columns = new ArrayList<>();
        DatabaseMetaData metaData = ctx.metaData();
        String catalog = ctx.catalog();

        // 先获取主键字段集合
        Set<String> primaryKeys = getPrimaryKeys(metaData, catalog, tableName);

        // 遍历所有字段
        try (ResultSet rs = metaData.getColumns(catalog, null, tableName, "%")) {
            while (rs.next()) {
                DbColumnMeta column = new DbColumnMeta();
                column.setSnapshotId(ctx.snapshotId());
                column.setTableMetaId(tableMetaId);
                column.setDatasourceId(ctx.datasourceId());
                column.setTableName(tableName);
                column.setColumnName(rs.getString("COLUMN_NAME"));
                column.setColumnComment(rs.getString("REMARKS"));
                column.setDataType(buildDataType(rs));
                column.setIsNullable("YES".equals(rs.getString("IS_NULLABLE")) ? 1 : 0);
                column.setColumnDefault(rs.getString("COLUMN_DEF"));
                column.setIsPrimaryKey(primaryKeys.contains(rs.getString("COLUMN_NAME")) ? 1 : 0);
                column.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                column.setGovernanceStatus(DbColumnMeta.GOVERNANCE_DISCOVERED);
                columns.add(column);
            }
        }

        // Phase 2 #12: 列元数据采样值采集（PET-SQL, arXiv:2403.09732）
        // 对每列执行 SELECT DISTINCT ... LIMIT 5，结果存入 sampleValues 字段
        for (DbColumnMeta column : columns) {
            try (Statement stmt = ctx.connection().createStatement()) {
                stmt.setQueryTimeout(2);
                // 列名用反引号转义防 SQL 注入
                String sql = "SELECT DISTINCT `" + column.getColumnName().replace("`", "``")
                    + "` FROM `" + tableName.replace("`", "``") + "` LIMIT 5";
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    StringBuilder samples = new StringBuilder();
                    int count = 0;
                    while (rs.next() && count < 5) {
                        String val = rs.getString(1);
                        if (val != null) {
                            if (count > 0) samples.append(",");
                            // 截断过长值
                            samples.append(val.length() > 50 ? val.substring(0, 50) : val);
                            count++;
                        }
                    }
                    if (samples.length() > 0) {
                        column.setSampleValues(samples.toString());
                    }
                }
            } catch (SQLException e) {
                log.warn("采样失败 {}.{}: {}", tableName, column.getColumnName(), e.getMessage());
            }
        }
        return columns;
    }

    /**
     * 获取指定表的主键字段名集合。
     *
     * @param metaData  数据库元数据
     * @param catalog   数据库名
     * @param tableName 表名
     * @return 主键字段名集合
     * @throws SQLException 数据库访问异常
     */
    private Set<String> getPrimaryKeys(DatabaseMetaData metaData, String catalog, String tableName) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, null, tableName)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    /**
     * 构建完整的数据类型字符串（含精度和小数位）。
     * <p>
     * 例如：VARCHAR(255)、DECIMAL(10,2)、INT。
     * </p>
     *
     * @param rs 字段结果集
     * @return 格式化的数据类型字符串
     * @throws SQLException 数据库访问异常
     */
    private String buildDataType(ResultSet rs) throws SQLException {
        String typeName = rs.getString("TYPE_NAME");
        int columnSize = rs.getInt("COLUMN_SIZE");
        int decimalDigits = rs.getInt("DECIMAL_DIGITS");

        // 有精度和小数位的类型（如 DECIMAL(10,2)）
        if (columnSize > 0 && decimalDigits > 0) {
            return "%s(%d,%d)".formatted(typeName, columnSize, decimalDigits);
        }
        // 只有长度的类型（如 VARCHAR(255)）
        else if (columnSize > 0 && needsSize(typeName)) {
            return "%s(%d)".formatted(typeName, columnSize);
        }
        return typeName;
    }

    /**
     * 判断类型名是否需要显示长度。
     * <p>
     * CHAR、VARCHAR、BINARY、VARBINARY、BIT 等类型需要显示长度。
     * </p>
     *
     * @param typeName 类型名
     * @return 是否需要显示长度
     */
    private boolean needsSize(String typeName) {
        String upper = typeName.toUpperCase();
        return upper.contains("CHAR") || upper.contains("BINARY") || upper.equals("BIT");
    }
}
