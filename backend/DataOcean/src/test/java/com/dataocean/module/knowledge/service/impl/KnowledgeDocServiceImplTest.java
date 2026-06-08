package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReviewTaskMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import org.junit.jupiter.api.AfterEach;
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

@ExtendWith(MockitoExtension.class)
class KnowledgeDocServiceImplTest {

    @Mock
    private KnowledgeDocMapper knowledgeDocMapper;
    @Mock
    private KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private KnowledgeReviewTaskMapper knowledgeReviewTaskMapper;
    @Mock
    private VectorIndexTaskService vectorIndexTaskService;
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
    private FieldTagMapper fieldTagMapper;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private KnowledgeDocServiceImpl knowledgeDocService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void generateDraftPassesColumnConfidenceScoreToPython() {
        setLoginUser();
        long docId = 1L;
        long datasourceId = 10L;
        long snapshotId = 5L;

        KnowledgeDoc doc = KnowledgeDoc.builder()
                .id(docId)
                .datasourceId(datasourceId)
                .title("skills.md")
                .currentVersion(0)
                .build();
        when(knowledgeDocMapper.selectById(docId)).thenReturn(doc);

        DbTableMeta table = new DbTableMeta();
        table.setId(100L);
        table.setDatasourceId(datasourceId);
        table.setTableName("orders");
        table.setTableComment("订单表");
        when(dbTableMetaMapper.selectList(any(Wrapper.class))).thenReturn(List.of(table));

        DbColumnMeta column = new DbColumnMeta();
        column.setId(200L);
        column.setTableMetaId(table.getId());
        column.setDatasourceId(datasourceId);
        column.setTableName("orders");
        column.setColumnName("pay_amount");
        column.setDataType("DECIMAL(10,2)");
        column.setColumnComment("实付金额");
        column.setIsPrimaryKey(0);
        column.setConfidenceScore(92);
        when(dbColumnMetaMapper.selectList(any(Wrapper.class))).thenReturn(List.of(column));
        when(tableRelationMapper.selectList(any(Wrapper.class))).thenReturn(List.<TableRelation>of());
        when(pythonKnowledgeClient.generateDraft(eq(snapshotId), eq(datasourceId), anyList(), anyList(), anyList()))
                .thenReturn(Map.of("content", "generated skills"));
        when(dependencySnapshotBuilder.build(eq(datasourceId), eq(snapshotId), eq("AI_GENERATED"), any()))
                .thenReturn("{}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Object> callback = (Consumer<Object>) invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        String content = knowledgeDocService.generateDraft(docId, snapshotId);

        assertThat(content).isEqualTo("generated skills");

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Map<String, Object>>> tablesCaptor = ArgumentCaptor.forClass(List.class);
        verify(pythonKnowledgeClient).generateDraft(
                eq(snapshotId),
                eq(datasourceId),
                tablesCaptor.capture(),
                anyList(),
                anyList()
        );

        List<Map<String, Object>> tablesMetadata = tablesCaptor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) tablesMetadata.get(0).get("columns");
        assertThat(columns.get(0))
                .containsEntry("column_name", "pay_amount")
                .containsEntry("confidence_score", 92);
        verify(knowledgeDocVersionMapper).insert(any(KnowledgeDocVersion.class));
    }

    private void setLoginUser() {
        LoginUser loginUser = new LoginUser(
                7L,
                "admin",
                "password",
                "管理员",
                List.of("ADMIN"),
                List.of("knowledge:manage"),
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
