package com.dataocean.module.knowledge.support;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
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

    @Test void structureCoverageCannotHideMissingReviewedBusinessFacts(){
        Fixture fixture=new Fixture();
        when(fixture.business.facts(DATASOURCE_ID,SNAPSHOT_ID)).thenReturn(List.of(java.util.Map.of("factType","BUSINESS_RELATION","sourceIds",List.of("77"))));
        assertThatThrownBy(()->fixture.validator.validateCoverage(DATASOURCE_ID,SNAPSHOT_ID,List.of(document())))
                .hasMessageContaining("业务知识");
    }

    @Test
    void acceptsExactFullCoverageFromOneSnapshot() {
        Fixture fixture = new Fixture();
        fixture.validator.validate(DATASOURCE_ID, SNAPSHOT_ID, document());
    }

    @Test
    void acceptsPartialDocumentsButRequiresCompleteSet() {
        Fixture fixture = new Fixture();
        int split = document().indexOf("#### `order_id`");
        String first = document().substring(0, split);
        String second = document().substring(split);
        fixture.validator.validatePart(DATASOURCE_ID, SNAPSHOT_ID, first);
        fixture.validator.validatePart(DATASOURCE_ID, SNAPSHOT_ID, second);
        fixture.validator.validateCoverage(DATASOURCE_ID, SNAPSHOT_ID, List.of(first, second));
        assertThatThrownBy(() -> fixture.validator.validateCoverage(DATASOURCE_ID, SNAPSHOT_ID, List.of(first)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("全部表和字段");
    }
    @Test void rawForeignKeyNeedsHumanBusinessApprovalBeforeJoinPublication(){
        Fixture fixture=new Fixture();var relation=new com.dataocean.module.metadata.entity.po.TableRelation();relation.setId(301L);relation.setDatasourceId(DATASOURCE_ID);relation.setSnapshotId(SNAPSHOT_ID);
        relation.setSourceTable("sales_orders");relation.setSourceColumn("order_id");relation.setTargetTable("sales_orders");relation.setTargetColumn("order_id");relation.setRelationType("FK");relation.setReviewStatus("CONFIRMED");
        when(fixture.relations.selectById(301L)).thenReturn(relation);
        String marker="\n## 12. 关系\n### 自关联\n<!-- dataocean-fact: {\"snapshotId\":8801,\"factType\":\"JOIN_PATH\",\"sourceIds\":[301],\"dependencies\":[\"table:sales_orders\",\"column:sales_orders.order_id\"],\"reviewStatus\":\"APPROVED\",\"governanceStatus\":\"NORMAL\"} -->\n- Condition: `sales_orders.order_id = sales_orders.order_id`\n";
        assertThatThrownBy(()->fixture.validator.validatePart(DATASOURCE_ID,SNAPSHOT_ID,document()+marker)).isInstanceOf(BusinessException.class).hasMessageContaining("未确认");
        fixture.validator.validatePart(DATASOURCE_ID,SNAPSHOT_ID,document()+marker.replace("JOIN_PATH","JOIN_CANDIDATE").replace("\"reviewStatus\":\"APPROVED\"","\"reviewStatus\":\"PENDING\""));
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
        private final com.dataocean.module.fieldsemantic.service.BusinessKnowledgeService business=mock(com.dataocean.module.fieldsemantic.service.BusinessKnowledgeService.class);
        private final DbTableMetaMapper tables = mock(DbTableMetaMapper.class);
        private final DbColumnMetaMapper columns = mock(DbColumnMetaMapper.class);
        private final TableRelationMapper relations = mock(TableRelationMapper.class);
        private final MetadataRelationshipMapper lineage = mock(MetadataRelationshipMapper.class);
        private final KnowledgeSnapshotFactValidator validator = new KnowledgeSnapshotFactValidator(
                tables, columns, relations, lineage, new ObjectMapper(), mock(com.dataocean.module.knowledge.service.impl.GlossaryKnowledgeSourceServiceImpl.class),
                mock(com.dataocean.module.knowledge.service.impl.KnowledgeSemanticServiceImpl.class),
                mock(com.dataocean.module.fieldsemantic.service.FieldSemanticService.class),
                business);

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
