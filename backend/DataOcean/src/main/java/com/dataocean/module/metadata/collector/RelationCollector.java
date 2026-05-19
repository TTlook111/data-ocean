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

@Slf4j
@Component
public class RelationCollector {

    public List<TableRelation> collect(Connection connection, Long datasourceId, Long snapshotId,
                                       String tableName) throws SQLException {
        List<TableRelation> relations = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();

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
