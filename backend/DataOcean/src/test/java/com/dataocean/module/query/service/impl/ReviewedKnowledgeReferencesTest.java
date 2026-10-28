package com.dataocean.module.query.service.impl;

import com.dataocean.module.knowledge.service.impl.GlossaryKnowledgeSourceServiceImpl;
import com.dataocean.module.knowledge.service.impl.KnowledgeSemanticServiceImpl;
import com.dataocean.module.query.entity.po.QueryTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 独立审核来源的撤回合同：新任务过滤与在途任务复核缺一不可。 */
class ReviewedKnowledgeReferencesTest {
    private final IamS1QueryServiceImpl service = mock(IamS1QueryServiceImpl.class, CALLS_REAL_METHODS);
    private final GlossaryKnowledgeSourceServiceImpl glossary = mock(GlossaryKnowledgeSourceServiceImpl.class);
    private final KnowledgeSemanticServiceImpl semantic = mock(KnowledgeSemanticServiceImpl.class);
    private final ObjectMapper json = new ObjectMapper();

    ReviewedKnowledgeReferencesTest() {
        ReflectionTestUtils.setField(service, "objectMapper", json);
        ReflectionTestUtils.setField(service, "glossarySources", glossary);
        ReflectionTestUtils.setField(service, "semanticSources", semantic);
    }

    private QueryTask task(String type, String origin, Long snapshot) throws Exception {
        return QueryTask.builder().datasourceId(1L).activeMetadataSnapshotId(88L).ragSourceSnapshotId(87L)
                .reviewedKnowledgeRefsJson(json.writeValueAsString(List.of(Map.of(
                        "factType", type, "origin", origin, "sourceSnapshotId", snapshot,
                        "factSourceIds", List.of("9", "19"))))).build();
    }

    @Test void withdrawnGlossaryStopsBothRagAndDirectPlanningReferences() throws Exception {
        when(glossary.membershipValid(List.of("9", "19"))).thenReturn(true, true, false, false);
        var rag = task("GLOSSARY_TERM", "RAG", 87L);
        var direct = task("GLOSSARY_TERM", "GLOSSARY", 88L);
        assertThat(service.reviewedKnowledgeReferencesValid(rag)).isTrue();
        assertThat(service.reviewedKnowledgeReferencesValid(direct)).isTrue();
        assertThat(service.reviewedKnowledgeReferencesValid(rag)).isFalse();
        assertThat(service.reviewedKnowledgeReferencesValid(direct)).isFalse();
    }

    @Test void withdrawnSemanticNoteStopsInFlightReferences() throws Exception {
        when(semantic.membershipValid(List.of("9", "19"))).thenReturn(true, false);
        var task = task("SEMANTIC_NOTE", "RAG", 87L);
        assertThat(service.reviewedKnowledgeReferencesValid(task)).isTrue();
        assertThat(service.reviewedKnowledgeReferencesValid(task)).isFalse();
    }

    @Test void absentMalformedUnknownOrWrongSnapshotReferencesFailClosed() throws Exception {
        assertThat(service.reviewedKnowledgeReferencesValid(new QueryTask())).isFalse();
        var task = task("GLOSSARY_TERM", "RAG", 88L);
        assertThat(service.reviewedKnowledgeReferencesValid(task)).isFalse();
        assertThat(service.reviewedKnowledgeReferencesValid(task("SEMANTIC_NOTE", "GLOSSARY", 88L))).isFalse();
        assertThat(service.reviewedKnowledgeReferencesValid(task("OTHER", "RAG", 87L))).isFalse();
        task.setReviewedKnowledgeRefsJson("not-json");
        assertThat(service.reviewedKnowledgeReferencesValid(task)).isFalse();
        task.setReviewedKnowledgeRefsJson("null");
        assertThat(service.reviewedKnowledgeReferencesValid(task)).isFalse();
        task.setReviewedKnowledgeRefsJson("[]");
        assertThat(service.reviewedKnowledgeReferencesValid(task)).isTrue();
    }
}
