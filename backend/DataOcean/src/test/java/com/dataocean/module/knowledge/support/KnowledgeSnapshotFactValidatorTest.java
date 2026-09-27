package com.dataocean.module.knowledge.support;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSnapshotFactValidatorTest {
    private static final long DATASOURCE_ID = 701L;
    private static final long SNAPSHOT_ID = 8801L;

    @Test
    void acceptsExactFullCoverageFromOneSnapshot() {
        Fixture fixture = new Fixture();
        fixture.validator.validate(DATASOURCE_ID, SNAPSHOT_ID, document());
    }

    @Test
    void rejectsAnOmittedFieldMarker() {
        Fixture fixture = new Fixture();
        String missingField = document().replace(columnMarker(), "");
        assertThatThrownBy(() -> fixture.validator.validate(DATASOURCE_ID, SNAPSHOT_ID, missingField))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("全部表和字段");
    }

    @Test
    void rejectsAFieldMarkerThatClaimsAnotherColumnType() {
        Fixture fixture = new Fixture();
        String wrongType = document().replace("BIGINT", "VARCHAR");
        assertThatThrownBy(() -> fixture.validator.validate(DATASOURCE_ID, SNAPSHOT_ID, wrongType))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("类型");
    }

    private static String document() {
        return """
                ## 1. 文档来源
                snapshot 8801
                ## 2. 核心表与完整字段目录
                ### \u0060sales_orders\u0060
                <!-- dataocean-fact: {"snapshotId":8801,"factType":"TABLE_STRUCTURE","sourceIds":[10],"dependencies":["table:sales_orders"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"} -->
                - Snapshot table ID: \u006010\u0060
                - Table type: \u0060TABLE\u0060
                - Governance status: \u0060NORMAL\u0060
                - Business meaning: 待确认。
                #### \u0060order_id\u0060
                <!-- dataocean-fact: {"snapshotId":8801,"factType":"COLUMN_STRUCTURE","sourceIds":[10,101],"dependencies":["table:sales_orders","column:sales_orders.order_id"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"} -->
                - Column ID: \u0060101\u0060
                - Type: \u0060BIGINT\u0060
                - Primary key: \u0060true\u0060
                - Governance status: \u0060NORMAL\u0060
                - Business meaning: 待确认。
                ## 3. Confirmed Join Paths
                暂无
                ## 4. Unconfirmed relationship candidates
                暂无
                ## 5. Data lineage and field derivations
                暂无
                ## 6. 字段防坑指南
                暂无
                ## 7. 指标口径
                暂无
                ## 8. 常见查询场景
                暂无
                ## 9. Governance and use restrictions
                受 S1 控制
                """;
    }

    private static String columnMarker() {
        return "<!-- dataocean-fact: {\"snapshotId\":8801,\"factType\":\"COLUMN_STRUCTURE\",\"sourceIds\":[10,101],\"dependencies\":[\"table:sales_orders\",\"column:sales_orders.order_id\"],\"reviewStatus\":\"APPROVED\",\"governanceStatus\":\"NORMAL\"} -->";
    }

    private static final class Fixture {
        private final DbTableMetaMapper tables = mock(DbTableMetaMapper.class);
        private final DbColumnMetaMapper columns = mock(DbColumnMetaMapper.class);
        private final TableRelationMapper relations = mock(TableRelationMapper.class);
        private final MetadataRelationshipMapper lineage = mock(MetadataRelationshipMapper.class);
        private final KnowledgeSnapshotFactValidator validator = new KnowledgeSnapshotFactValidator(
                tables, columns, relations, lineage, new ObjectMapper());

        private Fixture() {
            DbTableMeta table = new DbTableMeta();
            table.setId(10L);
            table.setDatasourceId(DATASOURCE_ID);
            table.setSnapshotId(SNAPSHOT_ID);
            table.setTableName("sales_orders");
            table.setTableType("TABLE");
            table.setGovernanceStatus("NORMAL");
            when(tables.selectList(any())).thenReturn(List.of(table));

            DbColumnMeta column = new DbColumnMeta();
            column.setId(101L);
            column.setDatasourceId(DATASOURCE_ID);
            column.setSnapshotId(SNAPSHOT_ID);
            column.setTableMetaId(10L);
            column.setTableName("sales_orders");
            column.setColumnName("order_id");
            column.setDataType("BIGINT");
            column.setGovernanceStatus("NORMAL");
            when(columns.selectList(any())).thenReturn(List.of(column));
        }
    }
}
