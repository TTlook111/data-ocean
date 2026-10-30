package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.*;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.knowledge.entity.dto.*;
import com.dataocean.module.knowledge.entity.po.*;
import com.dataocean.module.knowledge.mapper.*;
import com.dataocean.module.glossary.service.GlossaryTermWorkflowService;
import com.dataocean.module.metadata.entity.po.*;
import com.dataocean.module.metadata.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeSemanticServiceImplTest {
    final KnowledgeDocMapper docs = mock(KnowledgeDocMapper.class);
    final KnowledgeDocVersionMapper versions = mock(KnowledgeDocVersionMapper.class);
    final KnowledgeSemanticCandidateMapper candidates = mock(KnowledgeSemanticCandidateMapper.class);
    final DbTableMetaMapper tables = mock(DbTableMetaMapper.class);
    final DbColumnMetaMapper columns = mock(DbColumnMetaMapper.class);
    final GlossaryKnowledgeSourceServiceImpl sources = mock(GlossaryKnowledgeSourceServiceImpl.class);
    final PythonKnowledgeClient python = mock(PythonKnowledgeClient.class);
    final KnowledgeSemanticServiceImpl service = new KnowledgeSemanticServiceImpl(docs, versions, candidates,
            tables, columns, sources, mock(GlossaryTermWorkflowService.class), python,
            new ObjectMapper().findAndRegisterModules(), mock(TransactionTemplate.class));
    KnowledgeDoc doc;
    KnowledgeSemanticCandidate candidate;

    @BeforeEach void setup() {
        var login = new LoginUser(7L, "editor", "", "维护者", List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
        doc = KnowledgeDoc.builder().id(1L).datasourceId(5L).version(3).currentVersion(2).status("DRAFT")
                .content("原有结构目录及人工说明").build();
        when(docs.selectForUpdate(1L)).thenReturn(doc);
        when(docs.selectById(1L)).thenReturn(doc);
        when(docs.updateById(any(KnowledgeDoc.class))).thenReturn(1);
        when(versions.selectOne(any())).thenReturn(KnowledgeDocVersion.builder().metadataSnapshotId(88L).dependencySnapshot("{}").build());
        var table = new DbTableMeta(); table.setId(10L); table.setTableName("orders");
        when(tables.selectList(any())).thenReturn(List.of(table));
        var column = new DbColumnMeta(); column.setTableName("orders"); column.setColumnName("id"); column.setDataType("bigint");
        when(columns.selectList(any())).thenReturn(List.of(column));
        candidate = new KnowledgeSemanticCandidate(); candidate.setId(20L); candidate.setDocId(1L);
        candidate.setDatasourceId(5L); candidate.setSnapshotId(88L); candidate.setBaseVersion(3);
        candidate.setCandidateText("待确认候选"); candidate.setDependenciesJson("[\"table:orders\",\"column:orders.id\"]");
        candidate.setInputJson("{\"glossaryFacts\":[]}");
        when(candidates.selectById(20L)).thenReturn(candidate);
    }
    @AfterEach void cleanup() { UserContext.clear(); SecurityContextHolder.clearContext(); }
    SemanticConfirmDTO request() {
        var request = new SemanticConfirmDTO(); request.setVersion(3); request.setConfirmed(true);
        var selection = new SemanticConfirmDTO.SelectionDTO(); selection.setId(20L); selection.setText("人工核实的订单编号含义");
        request.setSelections(List.of(selection)); return request;
    }
    @Test void explicitConfirmationAppendsToNewVersionAndPreservesOriginalContent() {
        service.confirm(1L, request());
        var version = ArgumentCaptor.forClass(KnowledgeDocVersion.class);
        verify(versions).insert(version.capture());
        assertThat(version.getValue().getContent()).startsWith("原有结构目录及人工说明")
                .contains("SEMANTIC_NOTE", "人工核实的订单编号含义");
        assertThat(version.getValue().getVersionNo()).isEqualTo(3);
        assertThat(doc.getStatus()).isEqualTo("DRAFT");
        assertThat(candidate.getConfirmedBy()).isEqualTo(7L);
    }
    @Test void candidateFromAnotherDocumentCannotBeAdopted() {
        candidate.setDocId(99L);
        assertThatThrownBy(() -> service.confirm(1L, request())).isInstanceOf(BusinessException.class);
        verify(versions, never()).insert(any(KnowledgeDocVersion.class));
        verify(candidates, never()).updateById(any(KnowledgeSemanticCandidate.class));
    }
    @Test void missingHumanConfirmationNeverCreatesKnowledgeVersion() {
        var request = request(); request.setConfirmed(false);
        assertThatThrownBy(() -> service.confirm(1L, request)).hasMessageContaining("核对业务含义");
        verify(versions, never()).insert(any(KnowledgeDocVersion.class));
    }
    @Test void providerFailureDoesNotOverwriteExistingKnowledge() {
        when(sources.inputs(5L, 88L)).thenReturn(Map.of("glossaryFacts", List.of()));
        when(python.supplementSemantics(any())).thenThrow(new BusinessException("模型不可用"));
        var request = new SemanticDraftDTO(); request.setVersion(3);
        assertThatThrownBy(() -> service.generate(1L, request)).hasMessageContaining("模型不可用");
        assertThat(doc.getContent()).isEqualTo("原有结构目录及人工说明");
        verify(versions, never()).insert(any(KnowledgeDocVersion.class));
        verify(candidates, never()).insert(any(KnowledgeSemanticCandidate.class));
    }
}
