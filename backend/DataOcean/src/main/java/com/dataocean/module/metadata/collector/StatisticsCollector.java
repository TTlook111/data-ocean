package com.dataocean.module.metadata.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计信息采集器。
 * <p>
 * 负责采集字段级别的统计信息，包括表行数估算、字段空值率、去重计数和 TopN 高频值。
 * 空值率基于前 1000 行采样计算，避免全表扫描影响性能。
 * </p>
 */
@Slf4j
@Component
public class StatisticsCollector {

    /**
     * 采集表的行数估算值。
     * <p>
     * 从 information_schema.TABLES 获取，为 MySQL 引擎的估算值（非精确值）。
     * </p>
     *
     * @param connection 数据库连接
     * @param tableName  表名
     * @return 行数估算值，失败时返回 null
     */
    public Long collectRowCount(Connection connection, String tableName) {
        String sql = "SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (var pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, getCatalog(connection));
            pstmt.setString(2, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("TABLE_ROWS");
                }
            }
        } catch (SQLException e) {
            log.warn("获取表 {} 行数估算失败", tableName, e);
        }
        return null;
    }

    /**
     * 采集字段的空值率。
     * <p>
     * 基于前 1000 行采样计算空值占比，结果保留 4 位小数。
     * </p>
     *
     * @param connection 数据库连接
     * @param tableName  表名
     * @param columnName 字段名
     * @return 空值率（0~1），失败时返回 null
     */
    public BigDecimal collectNullRate(Connection connection, String tableName, String columnName) {
        String sql = "SELECT COUNT(*) AS total, SUM(CASE WHEN `" + escapeIdentifier(columnName) + "` IS NULL THEN 1 ELSE 0 END) AS null_count FROM (SELECT `" + escapeIdentifier(columnName) + "` FROM `" + escapeIdentifier(tableName) + "` LIMIT 1000) t";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                long total = rs.getLong("total");
                long nullCount = rs.getLong("null_count");
                if (total == 0) return BigDecimal.ZERO;
                return BigDecimal.valueOf(nullCount)
                        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            }
        } catch (SQLException e) {
            log.warn("获取字段 {}.{} 空值率失败", tableName, columnName, e);
        }
        return null;
    }

    /**
     * 采集字段的 TopN 高频值。
     * <p>
     * 按出现次数降序排列，返回前 N 个非空值及其出现次数。
     * </p>
     *
     * @param connection 数据库连接
     * @param tableName  表名
     * @param columnName 字段名
     * @param n          返回的最大条数
     * @return TopN 值列表，每项包含 value 和 count
     */
    public List<Map<String, Object>> collectTopNValues(Connection connection, String tableName,
                                                       String columnName, int n) {
        List<Map<String, Object>> result = new ArrayList<>();
        String col = escapeIdentifier(columnName);
        String tbl = escapeIdentifier(tableName);
        String sql = "SELECT `" + col + "` AS val, COUNT(*) AS cnt FROM `" + tbl + "` WHERE `" + col + "` IS NOT NULL GROUP BY `" + col + "` ORDER BY cnt DESC LIMIT " + n;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", rs.getString("val"));
                entry.put("count", rs.getLong("cnt"));
                result.add(entry);
            }
        } catch (SQLException e) {
            log.warn("获取字段 {}.{} TopN值失败", tableName, columnName, e);
        }
        return result;
    }

    /**
     * 采集字段的去重计数。
     *
     * @param connection 数据库连接
     * @param tableName  表名
     * @param columnName 字段名
     * @return 去重后的不同值数量，失败时返回 null
     */
    public Long collectDistinctCount(Connection connection, String tableName, String columnName) {
        String sql = "SELECT COUNT(DISTINCT `" + escapeIdentifier(columnName) + "`) AS cnt FROM `" + escapeIdentifier(tableName) + "`";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong("cnt");
            }
        } catch (SQLException e) {
            log.warn("获取字段 {}.{} 去重计数失败", tableName, columnName, e);
        }
        return null;
    }

    /**
     * 转义 SQL 标识符中的反引号，防止 SQL 注入。
     *
     * @param identifier 标识符（表名或字段名）
     * @return 转义后的标识符
     */
    private String escapeIdentifier(String identifier) {
        return identifier.replace("`", "``");
    }

    /**
     * 获取当前连接的数据库名（catalog）。
     *
     * @param connection 数据库连接
     * @return 数据库名，异常时返回空字符串
     */
    private String getCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException e) {
            return "";
        }
    }
}
