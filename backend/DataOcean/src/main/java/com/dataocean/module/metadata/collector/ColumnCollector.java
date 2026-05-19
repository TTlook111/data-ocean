package com.dataocean.module.metadata.collector;

import com.dataocean.module.metadata.entity.DbColumnMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class ColumnCollector {

    public List<DbColumnMeta> collect(Connection connection, Long datasourceId, Long snapshotId,
                                      String tableName, Long tableMetaId) throws SQLException {
        List<DbColumnMeta> columns = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();

        Set<String> primaryKeys = getPrimaryKeys(metaData, catalog, tableName);

        try (ResultSet rs = metaData.getColumns(catalog, null, tableName, "%")) {
            while (rs.next()) {
                DbColumnMeta column = new DbColumnMeta();
                column.setSnapshotId(snapshotId);
                column.setTableMetaId(tableMetaId);
                column.setDatasourceId(datasourceId);
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
        return columns;
    }

    private Set<String> getPrimaryKeys(DatabaseMetaData metaData, String catalog, String tableName) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, null, tableName)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String typeName = rs.getString("TYPE_NAME");
        int columnSize = rs.getInt("COLUMN_SIZE");
        int decimalDigits = rs.getInt("DECIMAL_DIGITS");

        if (columnSize > 0 && decimalDigits > 0) {
            return "%s(%d,%d)".formatted(typeName, columnSize, decimalDigits);
        } else if (columnSize > 0 && needsSize(typeName)) {
            return "%s(%d)".formatted(typeName, columnSize);
        }
        return typeName;
    }

    private boolean needsSize(String typeName) {
        String upper = typeName.toUpperCase();
        return upper.contains("CHAR") || upper.contains("BINARY") || upper.equals("BIT");
    }
}
