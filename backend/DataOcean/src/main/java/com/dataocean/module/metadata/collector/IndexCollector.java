package com.dataocean.module.metadata.collector;

import lombok.Data;
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
public class IndexCollector {

    public List<IndexInfo> collect(Connection connection, String tableName) throws SQLException {
        List<IndexInfo> indexes = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();

        try (ResultSet rs = metaData.getIndexInfo(catalog, null, tableName, false, true)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                if (indexName == null) continue;

                IndexInfo info = new IndexInfo();
                info.setTableName(tableName);
                info.setIndexName(indexName);
                info.setColumnName(rs.getString("COLUMN_NAME"));
                info.setNonUnique(rs.getBoolean("NON_UNIQUE"));
                info.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                indexes.add(info);
            }
        }
        return indexes;
    }

    @Data
    public static class IndexInfo {
        private String tableName;
        private String indexName;
        private String columnName;
        private boolean nonUnique;
        private int ordinalPosition;
    }
}
