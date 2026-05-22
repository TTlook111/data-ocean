package com.dataocean.module.metadata.collector;

import com.dataocean.module.metadata.entity.TableRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表关系采集器。
 * <p>
 * 通过 JDBC DatabaseMetaData 接口采集指定表的外键关系（导入键），
 * 记录源表/字段到目标表/字段的引用关系。外键关系的置信度为 1.0。
 * </p>
 */
@Slf4j
@Component
public class RelationCollector {

    /**
     * 采集指定表的所有外键关系。
     *
     * @param connection   数据库连接
     * @param datasourceId 数据源ID
     * @param snapshotId   快照ID
     * @param tableName    表名（作为外键所在的源表）
     * @return 表关系列表
     * @throws SQLException 数据库访问异常
     */
    public List<TableRelation> collect(Connection connection, Long datasourceId, Long snapshotId,
                                       String tableName) throws SQLException {
        List<TableRelation> relations = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();

        // 获取该表导入的外键（即该表引用了哪些其他表）
        try (ResultSet rs = metaData.getImportedKeys(catalog, null, tableName)) {
            while (rs.next()) {
                TableRelation relation = new TableRelation();
                relation.setSnapshotId(snapshotId);
                relation.setDatasourceId(datasourceId);
                relation.setSourceTable(tableName);
                relation.setSourceColumn(rs.getString("FKCOLUMN_NAME"));
                relation.setTargetTable(rs.getString("PKTABLE_NAME"));
                relation.setTargetColumn(rs.getString("PKCOLUMN_NAME"));
                relation.setRelationType(TableRelation.TYPE_FK);
                relation.setConfidence(BigDecimal.ONE);
                relations.add(relation);
            }
        }
        return relations;
    }
}
