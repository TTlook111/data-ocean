package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.po.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.po.KnowledgeDocVersion;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import com.dataocean.module.knowledge.support.KnowledgeOwnershipValidator;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识文档发布服务测试。
 * <p>
 * 原 KnowledgeDocServiceImpl 测试，拆分后测试 KnowledgeDocPublishService。
 * 重点验证 AI 草稿生成时字段置信度等元数据正确传递给 Python 服务。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocServiceImplTest {

    @Mock
    private GlossaryKnowledgeSourceServiceImpl glossarySources;
    @Mock
    private KnowledgeDocMapper knowledgeDocMapper;
    @Mock
    private KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    @Mock
    private PythonKnowledgeClient pythonKnowledgeClient;
    @Mock
    private PythonRagClient pythonRagClient;
    @Mock
    private KnowledgeDependencySnapshotBuilder dependencySnapshotBuilder;
    @Mock
    private DbTableMetaMapper dbTableMetaMapper;
    @Mock
    private DbColumnMetaMapper dbColumnMetaMapper;
    @Mock
    private TableRelationMapper tableRelationMapper;
    @Mock
    private MetadataRelationshipMapper metadataRelationshipMapper;
    @Mock
    private MetadataEntityService metadataEntityService;
    @Mock
    private FieldTagMapper fieldTagMapper;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private KnowledgeDocHelper helper;
    /**
     * 来源快照归属校验：规则本身由 KnowledgeVersionOwnershipTest / KnowledgeDocPublishServiceOwnershipTest
     * 用真实校验器覆盖，这里只按 mock 处理，避免与用例关注点混在一起。
     */
    @Mock
    private KnowledgeOwnershipValidator ownershipValidator;
    @Mock
    private com.dataocean.module.fieldsemantic.service.FieldSemanticService fieldSemanticService;
    @Mock
    private com.dataocean.module.fieldsemantic.service.BusinessKnowledgeService businessKnowledgeService;

    @InjectMocks
    private KnowledgeDocPublishService publishService;

    @BeforeEach
    void stubSuccessfulDocumentUpdate() {
        when(knowledgeDocMapper.updateById(any(KnowledgeDoc.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void generateDraftExcludesDynamicConfidenceFromKnowledgeInput() {
        setLoginUser();
        long docId = 1L;
        long datasourceId = 10L;
        long snapshotId = 5L;

        KnowledgeDoc doc = KnowledgeDoc.builder()
                .id(docId)
                .datasourceId(datasourceId)
                .title("skills.md").status("DRAFT").version(0)
                .currentVersion(0)
                .build();
        when(helper.requireDoc(docId)).thenReturn(doc);

        DbTableMeta table = new DbTableMeta();
        table.setId(100L);
        table.setDatasourceId(datasourceId);
        table.setSnapshotId(snapshotId);
        table.setTableName("orders");
        table.setTableComment("订单表");
        when(dbTableMetaMapper.selectList(any(Wrapper.class))).thenReturn(List.of(table));

        DbColumnMeta column = new DbColumnMeta();
        column.setId(200L);
        column.setTableMetaId(table.getId());
        column.setDatasourceId(datasourceId);
        column.setSnapshotId(snapshotId);
        column.setTableName("orders");
        column.setColumnName("pay_amount");
        column.setDataType("DECIMAL(10,2)");
        column.setColumnComment("实付金额");
        column.setIsPrimaryKey(0);
        column.setConfidenceScore(92);
        when(dbColumnMetaMapper.selectList(any(Wrapper.class))).thenReturn(List.of(column));
        when(tableRelationMapper.selectList(any(Wrapper.class))).thenReturn(List.<TableRelation>of());
        when(glossarySources.inputs(datasourceId, snapshotId)).thenReturn(Map.of("glossaryFacts", List.of(), "excludedTerms", List.of()));
        when(pythonKnowledgeClient.generateSnapshotDraft(any()))
                .thenReturn(Map.of("content", "generated skills", "coverage", Map.of(
                        "snapshotId", snapshotId, "tableIds", List.of(100L), "columnIds", List.of(200L),
                        "tableCoverage", 1.0, "columnCoverage", 1.0)));
        when(dependencySnapshotBuilder.build(eq(datasourceId), eq(snapshotId), eq("SNAPSHOT_GENERATED"), any()))
                .thenReturn("{}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Object> callback = (Consumer<Object>) invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        String content = publishService.generateDraft(docId, snapshotId);

        assertThat(content).isEqualTo("generated skills");

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonKnowledgeClient).generateSnapshotDraft(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).containsEntry("snapshot_id", snapshotId).containsEntry("datasource_id", datasourceId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tablesMetadata = (List<Map<String, Object>>) requestCaptor.getValue().get("tables_metadata");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) tablesMetadata.get(0).get("columns");
        assertThat(columns.get(0))
                .containsEntry("column_name", "pay_amount")
                .doesNotContainKey("confidence_score");
        verify(knowledgeDocVersionMapper).insert(any(KnowledgeDocVersion.class));
    }

    private void setLoginUser() {
        LoginUser loginUser = new LoginUser(
                7L,
                "admin",
                "password",
                "管理员",
                List.of(new SimpleGrantedAuthority("knowledge:manage"))
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser,
                null,
                loginUser.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
