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

@Slf4j
@Component
public class TableCollector {

    private static final int BATCH_SIZE = 100;
    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    public List<DbTableMeta> collect(Connection connection, Long datasourceId, Long snapshotId) throws SQLException {
        List<DbTableMeta> tables = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();

        try (ResultSet rs = metaData.getTables(catalog, null, "%", TABLE_TYPES)) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                String remarks = rs.getString("REMARKS");

                DbTableMeta table = new DbTableMeta();
                table.setSnapshotId(snapshotId);
                table.setDatasourceId(datasourceId);
                table.setTableName(tableName);
                table.setTableComment(remarks);
                table.setTableType(tableType);
                table.setGovernanceStatus(DbTableMeta.GOVERNANCE_DISCOVERED);
                tables.add(table);
            }
        }

        enrichFromInformationSchema(connection, catalog, tables);
        log.info("采集到 {} 张表", tables.size());
        return tables;
    }

    private void enrichFromInformationSchema(Connection connection, String catalog, List<DbTableMeta> tables) throws SQLException {
        if (tables.isEmpty()) return;

        String sql = "SELECT TABLE_NAME, TABLE_COMMENT, ENGINE, TABLE_COLLATION, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";

        try (var pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, catalog);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    tables.stream()
                            .filter(t -> t.getTableName().equals(tableName))
                            .findFirst()
                            .ifPresent(t -> {
                                try {
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
                            });
                }
            }
        }
    }

    public int getBatchSize() {
        return BATCH_SIZE;
    }
}
