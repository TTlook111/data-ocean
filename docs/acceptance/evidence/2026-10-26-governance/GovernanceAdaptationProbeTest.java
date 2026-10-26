package com.dataocean.module.query.service.impl;

import com.dataocean.module.knowledge.service.impl.KnowledgeDocPublishService;
import com.dataocean.module.knowledge.support.KnowledgeSnapshotFactValidator;
import com.dataocean.module.knowledge.entity.po.*;
import com.dataocean.module.knowledge.mapper.*;
import com.dataocean.module.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 只复制到临时源码副本运行；正确行为的断言失败用于保留可复现缺陷。 */
class GovernanceAdaptationProbeTest {
    private List<String> dependencies(Class<?> type, String source, String target) {
        Object service = mock(type, CALLS_REAL_METHODS);
        return ReflectionTestUtils.invokeMethod(service, "lineageDependencies", source, target);
    }

    @Test void publisherTableFqnKeepsActualTables() {
        assertThat(dependencies(KnowledgeDocPublishService.class, "ds.db.orders", "ds.db.rollups"))
                .containsExactly("table:orders", "table:rollups");
    }

    @Test void validatorTableFqnKeepsActualTables() {
        assertThat(dependencies(KnowledgeSnapshotFactValidator.class, "ds.db.orders", "ds.db.rollups"))
                .containsExactly("table:orders", "table:rollups");
    }

    @Test void columnFqnHasBothTableAndColumnDependencies() {
        for (Class<?> type : List.of(KnowledgeDocPublishService.class, KnowledgeSnapshotFactValidator.class)) {
            assertThat(dependencies(type, "ds.db.orders.amount", "ds.db.rollups.total"))
                    .containsExactly("table:orders", "column:orders.amount", "table:rollups", "column:rollups.total");
        }
    }

    private List<Map<String, Object>> readChunk(String type, boolean withColumns) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RagIndexBuildChunk.class);
        IamS1QueryServiceImpl service = mock(IamS1QueryServiceImpl.class, CALLS_REAL_METHODS);
        RagIndexBuildChunkMapper memberships = mock(RagIndexBuildChunkMapper.class);
        KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
        ReflectionTestUtils.setField(service, "ragIndexBuildChunkMapper", memberships);
        ReflectionTestUtils.setField(service, "knowledgeChunkMapper", chunks);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        RagIndexBuild build = new RagIndexBuild();
        build.setBuildId("offline-build"); build.setDatasourceId(1L); build.setSourceSnapshotId(88L);
        String deps = withColumns ? "[\"table:orders\",\"column:orders.id\"]" : "[\"table:orders\"]";
        RagIndexBuildChunk member = new RagIndexBuildChunk();
        member.setChunkId(10L); member.setBuildId("offline-build"); member.setDatasourceId(1L);
        member.setSourceSnapshotId(88L); member.setFactReviewStatus("APPROVED");
        member.setResourceDependencies(deps); member.setFactSourceIds("[\"401\"]");
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(10L); chunk.setMetadataSnapshotId(88L); chunk.setReviewStatus("APPROVED");
        chunk.setFactReviewStatus("APPROVED"); chunk.setGovernanceStatus("NORMAL");
        chunk.setRelatedTables("[\"orders\"]"); chunk.setRelatedColumns(withColumns ? "[\"orders.id\"]" : "[]");
        chunk.setFactType(type); chunk.setChunkType("LINEAGE"); chunk.setChunkText("isolated fixture");
        when(memberships.selectList(any())).thenReturn(List.of(member));
        when(chunks.selectBatchIds(any())).thenReturn(List.of(chunk));
        return ReflectionTestUtils.invokeMethod(service, "loadKnowledgeChunks", build, 1L,
                Set.of("table:orders", "column:orders.id"));
    }

    @Test void tableStructureSurvivesJavaAdmission() {
        assertThat(readChunk("TABLE_STRUCTURE", false)).hasSize(1);
    }

    @Test void tableLineageSurvivesJavaAdmission() {
        assertThat(readChunk("LINEAGE", false)).hasSize(1);
    }

    @Test void columnDerivationSurvivesJavaAdmission() {
        assertThat(readChunk("DERIVED_FROM", true)).hasSize(1);
    }

    @Test void completionWithNoSuccessfulCasMustNotEmitAudit() throws Exception {
        // 复用现有安全完成夹具；Mockito 默认 update=0 表示没有 PROCESSING→COMPLETED 写入。
        IamS1QueryServiceImplTest fixture = new IamS1QueryServiceImplTest();
        try (AutoCloseable mocks = MockitoAnnotations.openMocks(fixture)) {
            IamS1QueryServiceImplTest.initMyBatisTableInfo();
            fixture.setUp();
            fixture.completePersistsOnlyWhenSourceTraceAndCurrentSnapshotAreComplete();
            AuditLogService audit = (AuditLogService) ReflectionTestUtils.getField(fixture, "auditLogService");
            verify(audit, never()).recordAudit(17L);
        }
    }
}
