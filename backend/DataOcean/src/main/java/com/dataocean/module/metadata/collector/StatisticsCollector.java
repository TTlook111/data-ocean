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

@Slf4j
@Component
public class StatisticsCollector {

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

    private String escapeIdentifier(String identifier) {
        return identifier.replace("`", "``");
    }

    private String getCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException e) {
            return "";
        }
    }
}
