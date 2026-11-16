package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.service.BusinessKnowledgeService;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import com.dataocean.module.knowledge.entity.po.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.po.KnowledgeDocVersion;
import com.dataocean.module.knowledge.support.KnowledgeSnapshotFactValidator;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用实际来源校验器验证单篇状态与整组覆盖边界，不以宽松 mock 冒充分篇通过。 */
class KnowledgeSourceStatusCoverageTest {
    private static final String TABLE_PART = """
            ### sales_orders
            <!-- dataocean-fact: {"snapshotId":88,"factType":"TABLE_STRUCTURE","sourceIds":[10],"dependencies":["table:sales_orders"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"} -->
            - Snapshot table ID: `10`
            - Table type: `TABLE`
            """;
    private static final String COLUMN_PART = """
            #### order_id
            <!-- dataocean-fact: {"snapshotId":88,"factType":"COLUMN_STRUCTURE","sourceIds":[10,101],"dependencies":["table:sales_orders","column:sales_orders.order_id"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"} -->
            - Column ID: `101`
            - Type: `BIGINT`
            """;
    private static final String SEMANTIC_PART = """
            ### 已审核字段解释
            <!-- dataocean-fact: {"snapshotId":88,"factType":"FIELD_SEMANTIC","sourceIds":[1,2,3,4],"dependencies":["table:sales_orders","column:sales_orders.order_id"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"} -->
            - 字段含义: 合成订单唯一编号。
            """;

    @Test void eachLegitimateSplitPartIsValidButNotAnEntireBuildManifest() {
        Fixture f = new Fixture();
        assertThat(f.status(TABLE_PART)).isTrue();
        assertThat(f.status(COLUMN_PART)).isTrue();
        f.validator.validateCoverage(5L, 88L, List.of(TABLE_PART, COLUMN_PART));
        assertThatThrownBy(() -> f.validator.validateCoverage(5L, 88L, List.of(TABLE_PART)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("全部表和字段");
    }

    @Test void semanticOnlyPartUsesItsPinnedReviewedFactsWithoutInventingStructureCoverage() {
        Fixture f = new Fixture();
        assertThat(f.status(SEMANTIC_PART)).isTrue();
        verify(f.fields).validateKnowledgeFact(eq(5L), eq(88L), anyMap(), contains("合成订单唯一编号"));
        assertThatThrownBy(() -> f.validator.validateCoverage(5L, 88L, List.of(SEMANTIC_PART)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("全部表和字段");
    }

    @Test void missingMarkerOrDifferentPinnedSnapshotStillFails() {
        Fixture f = new Fixture();
        assertThat(f.status("只有标题和无来源说明")).isFalse();
        assertThat(f.status(COLUMN_PART.replace("\"snapshotId\":88", "\"snapshotId\":89"))).isFalse();
    }

    @Test void missingReferencedColumnOrTableCannotBeHiddenByPartialCoverage() {
        Fixture f = new Fixture();
        when(f.columns.selectList(any())).thenReturn(List.of());
        assertThat(f.status(COLUMN_PART)).isFalse();
        when(f.tables.selectList(any())).thenReturn(List.of());
        assertThat(f.status(TABLE_PART)).isFalse();
    }

    @Test void changedTypeOrBlockedGovernanceStillInvalidatesTheDeclaredFact() {
        Fixture f = new Fixture();
        assertThat(f.status(COLUMN_PART.replace("Type: `BIGINT`", "Type: `VARCHAR`"))).isFalse();
        f.table.setGovernanceStatus("BLOCKED");
        assertThat(f.status(TABLE_PART)).isFalse();
    }

    @Test void withdrawnOrHashChangedFieldReviewStillPropagatesToInvalidStatus() {
        Fixture f = new Fixture();
        doThrow(new BusinessException("当前审核修订、hash 或依赖已变化")).when(f.fields)
                .validateKnowledgeFact(eq(5L), eq(88L), anyMap(), anyString());
        assertThat(f.status(SEMANTIC_PART)).isFalse();
        verify(f.fields).validateKnowledgeFact(eq(5L), eq(88L), anyMap(), anyString());
    }

    private static class Fixture {
        final KnowledgeDocHelper helper = mock(KnowledgeDocHelper.class);
        final DbTableMetaMapper tables = mock(DbTableMetaMapper.class);
        final DbColumnMetaMapper columns = mock(DbColumnMetaMapper.class);
        final FieldSemanticService fields = mock(FieldSemanticService.class);
        final DbTableMeta table = new DbTableMeta();
        final KnowledgeSnapshotFactValidator validator = new KnowledgeSnapshotFactValidator(
                tables, columns, mock(TableRelationMapper.class), mock(MetadataRelationshipMapper.class),
                new ObjectMapper(), mock(GlossaryKnowledgeSourceServiceImpl.class),
                mock(KnowledgeSemanticServiceImpl.class), fields, mock(BusinessKnowledgeService.class));
        final KnowledgeSourceStatusServiceImpl service = new KnowledgeSourceStatusServiceImpl(helper, validator);
        Fixture() {
            table.setId(10L); table.setDatasourceId(5L); table.setSnapshotId(88L);
            table.setTableName("sales_orders"); table.setTableType("TABLE"); table.setGovernanceStatus("NORMAL");
            DbColumnMeta col = new DbColumnMeta(); col.setId(101L); col.setTableMetaId(10L);
            col.setDatasourceId(5L); col.setSnapshotId(88L); col.setTableName("sales_orders");
            col.setColumnName("order_id"); col.setDataType("BIGINT"); col.setGovernanceStatus("NORMAL");
            when(tables.selectList(any())).thenReturn(List.of(table));
            when(columns.selectList(any())).thenReturn(List.of(col));
        }
        boolean status(String content) {
            when(helper.requireDoc(1L)).thenReturn(KnowledgeDoc.builder().id(1L)
                    .datasourceId(5L).currentVersion(2).content(content).build());
            when(helper.requireVersion(1L, 2)).thenReturn(KnowledgeDocVersion.builder()
                    .metadataSnapshotId(88L).content(content).build());
            return service.status(1L).valid();
        }
    }
}
