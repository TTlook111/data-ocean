package com.dataocean.module.metadata.collector;

import com.dataocean.module.metadata.entity.DbTableMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表元数据采集器。
 * <p>
 * 通过 JDBC DatabaseMetaData 接口采集数据库中所有表和视图的基本信息，
 * 并从 information_schema 补充引擎、字符集、行数估算、数据大小等扩展信息。
 * </p>
 */
@Slf4j
@Component
public class TableCollector {

    /** 批量处理大小 */
    private static final int BATCH_SIZE = 100;

    /** 采集的表类型：普通表和视图 */
    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    /**
     * 采集指定数据源的所有表元数据。
     *
     * @param ctx 采集上下文
     * @return 表元数据列表
     * @throws SQLException 数据库访问异常
     */
    public List<DbTableMeta> collect(CollectorContext ctx) throws SQLException {
        List<DbTableMeta> tables = new ArrayList<>();
        DatabaseMetaData metaData = ctx.metaData();
        String catalog = ctx.catalog();

        // 通过 DatabaseMetaData 获取所有表和视图
        try (ResultSet rs = metaData.getTables(catalog, null, "%", TABLE_TYPES)) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                String remarks = rs.getString("REMARKS");

                DbTableMeta table = new DbTableMeta();
                table.setSnapshotId(ctx.snapshotId());
                table.setDatasourceId(ctx.datasourceId());
                table.setTableName(tableName);
                table.setTableComment(remarks);
                table.setTableType(tableType);
                table.setGovernanceStatus(DbTableMeta.GOVERNANCE_DISCOVERED);
                tables.add(table);
            }
        }

        // 从 information_schema 补充扩展信息
        enrichFromInformationSchema(ctx.connection(), catalog, tables);
        log.info("采集到 {} 张表", tables.size());
        return tables;
    }

    /**
     * 从 information_schema.TABLES 补充表的扩展信息。
     * <p>
     * 包括表注释、存储引擎、字符集、行数估算、数据大小和索引大小。
     * </p>
     *
     * @param connection 数据库连接
     * @param catalog    数据库名
     * @param tables     待补充的表列表
     * @throws SQLException 数据库访问异常
     */
    private void enrichFromInformationSchema(Connection connection, String catalog, List<DbTableMeta> tables) throws SQLException {
        if (tables.isEmpty()) return;

        // 构建表名到实体的映射，便于快速查找
        Map<String, DbTableMeta> tableMap = tables.stream()
                .collect(java.util.stream.Collectors.toMap(DbTableMeta::getTableName, t -> t));

        String sql = "SELECT TABLE_NAME, TABLE_COMMENT, ENGINE, TABLE_COLLATION, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";

        try (var pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, catalog);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    DbTableMeta t = tableMap.get(tableName);
                    if (t != null) {
                        try {
                            // 优先使用 information_schema 中的注释（更完整）
                            String comment = rs.getString("TABLE_COMMENT");
                            if (comment != null && !comment.isEmpty()) {
                                t.setTableComment(comment);
                            }
                            t.setEngine(rs.getString("ENGINE"));
                            t.setTableCharset(rs.getString("TABLE_COLLATION"));
                            t.setRowCountEstimate(rs.getLong("TABLE_ROWS"));
                            t.setDataSizeBytes(rs.getLong("DATA_LENGTH"));
                            t.setIndexSizeBytes(rs.getLong("INDEX_LENGTH"));
                        } catch (SQLException e) {
                            log.warn("读取表 {} 的扩展信息失败", tableName, e);
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取批量处理大小。
     *
     * @return 批量大小
     */
    public int getBatchSize() {
        return BATCH_SIZE;
    }
}
