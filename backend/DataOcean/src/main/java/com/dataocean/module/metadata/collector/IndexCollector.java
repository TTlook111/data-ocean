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

/**
 * 索引信息采集器。
 * <p>
 * 通过 JDBC DatabaseMetaData 接口采集指定表的所有索引信息，
 * 包括索引名、关联字段、是否唯一、字段在索引中的序号等。
 * </p>
 */
@Slf4j
@Component
public class IndexCollector {

    /**
     * 采集指定表的所有索引信息。
     *
     * @param connection 数据库连接
     * @param tableName  表名
     * @return 索引信息列表
     * @throws SQLException 数据库访问异常
     */
    public List<IndexInfo> collect(Connection connection, String tableName) throws SQLException {
        List<IndexInfo> indexes = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();

        // 获取所有索引（包括非唯一索引），使用近似值加速查询
        try (ResultSet rs = metaData.getIndexInfo(catalog, null, tableName, false, true)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                // 跳过无名索引
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

    /**
     * 索引信息数据对象。
     * <p>
     * 记录单个索引条目的详细信息，复合索引会有多条记录（按 ordinalPosition 排序）。
     * </p>
     */
    @Data
    public static class IndexInfo {

        /** 所属表名 */
        private String tableName;

        /** 索引名称 */
        private String indexName;

        /** 索引关联的字段名 */
        private String columnName;

        /** 是否非唯一索引（true=非唯一，false=唯一） */
        private boolean nonUnique;

        /** 字段在索引中的序号位置 */
        private int ordinalPosition;
    }
}
