package com.dataocean.module.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestVO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Document;
import com.dataocean.module.knowledge.mapper.KnowledgeTestMapper;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.List;

import org.junit.jupiter.api.Test;

class KnowledgeReleasePreflightTest {

    /**
     * 新草稿保留原确认，发布准备只核对原确认的题单，并披露实际模型阻断。
     */
    @Test
    void keepsConfirmedRevisionWhenANewerDraftExists() {
        var catalog = mock(GovernanceCatalog.class);
        var documents = mock(KnowledgeDocumentMapper.class);
        var tests = mock(KnowledgeTestMapper.class);
        var references = mock(KnowledgeReferences.class);
        var models = mock(ModelRegistry.class);
        var json = mock(JsonCodec.class);
        var origin = mock(GovernanceCatalog.Source.class);
        when(origin.lifecycle()).thenReturn("ENABLED");
        when(origin.snapshotId()).thenReturn(10L);
        when(catalog.source(1, false)).thenReturn(origin);
        when(documents.get(1, 2)).thenReturn(new Document(2, 1, "新草稿", 20, 19L, 5L, null, 3));
        when(documents.references(19)).thenReturn(List.of());
        when(tests.reviewed(1, 2, 19)).thenReturn(List.of("number", "judgment"));
        when(json.read("number", KnowledgeTestVO.Content.class))
                .thenReturn(new KnowledgeTestVO.Content("NUMERIC", "CAPABILITY", null, 2L, null, null, null, "19"));
        when(json.read("judgment", KnowledgeTestVO.Content.class))
                .thenReturn(new KnowledgeTestVO.Content("EXPLANATION", "BOUNDARY", null, null, null, null, null, "19"));
        when(models.requireBinding("rag-embedding")).thenThrow(BizIllegalException.state("缺少模型"));

        var result = new KnowledgeReleasePreflight(catalog, documents, tests, references, models, json).inspect(1, 2);

        assertEquals("19", result.getDocumentRevisionId());
        assertEquals(2, result.getReviewedTests());
        assertNull(result.getEmbeddingRevisionId());
        assertEquals(List.of("知识检索向量化节点未分配可用模型"), result.getIssues());
        verify(documents).references(19);
    }

    /**
     * 停用来源且文档尚未确认时也直接披露来源阻断。
     */
    @Test
    void blocksDisabledSourceWithoutAConfirmedDocument() {
        var catalog = mock(GovernanceCatalog.class);
        var documents = mock(KnowledgeDocumentMapper.class);
        var origin = mock(GovernanceCatalog.Source.class);
        var models = mock(ModelRegistry.class);
        when(origin.lifecycle()).thenReturn("DISABLED");
        when(origin.snapshotId()).thenReturn(10L);
        when(catalog.source(1, false)).thenReturn(origin);
        when(documents.get(1, 2)).thenReturn(new Document(2, 1, "新草稿", 20, null, null, null, 1));
        when(models.requireBinding("rag-embedding")).thenReturn(30L);

        var result = new KnowledgeReleasePreflight(
                        catalog,
                        documents,
                        mock(KnowledgeTestMapper.class),
                        mock(KnowledgeReferences.class),
                        models,
                        mock(JsonCodec.class))
                .inspect(1, 2);

        assertEquals(List.of("当前数据源未启用或没有适用的成功目录", "请先确认可用于发布的文档修订"), result.getIssues());
    }
}
