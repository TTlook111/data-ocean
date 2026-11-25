package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Build;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Chunk;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Reference;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Evidence;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.model.service.ModelRegistry;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Plan;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * 复验按限定表名回读冻结发布时的授权、血缘及编译结果。
 */
class QueryCorpusTest {

    /** 同一源上的两个模式均可存在同名物理表。 */
    private static final long SOURCE = 72L;

    /** 当前轮次只固定这个发布版本。 */
    private static final long BINDING = 6L;

    /** 模拟平台库中的目录、冻结片段及已发布文档。 */
    private final KnowledgeVersionService versions = mock(KnowledgeVersionService.class);

    private final KnowledgeBuildMapper builds = mock(KnowledgeBuildMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final GovernanceCatalog catalog = mock(GovernanceCatalog.class);
    private final GovernanceKnowledgeService governance = mock(GovernanceKnowledgeService.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JsonCodec json = new JsonCodec(mapper);
    private final Source source = new Source(SOURCE, "物流", "ENABLED", 4L, 3L, 1, false, null, null, null, null);

    /** 仅替换平台读写，使用实际补查、片段验证和 SQL 编译。 */
    private final QueryCorpus corpus = new QueryCorpus(
            mock(KnowledgePublicationMapper.class),
            versions,
            mock(KnowledgeReleaseCandidateMapper.class),
            builds,
            documents,
            catalog,
            governance,
            mock(ModelRegistry.class),
            json);

    /**
     * 创建同一发布中两个不同物理对象的片段。
     */
    @BeforeEach
    void setUp() {
        var binding = mock(Binding.class);
        when(binding.id()).thenReturn(BINDING);
        when(binding.buildId()).thenReturn("verified-build");
        when(binding.documentRevisionId()).thenReturn(4L);
        when(versions.requireForQuery(SOURCE, BINDING)).thenReturn(binding);
        var build = mock(Build.class);
        when(build.verifiedChunks()).thenReturn(2);
        when(builds.get("verified-build")).thenReturn(build);
        when(builds.chunks("verified-build"))
                .thenReturn(List.of(
                        new Chunk(0, "异表正文", json.write(List.of(evidence(21, "TABLE", 21, 101))), "b"),
                        new Chunk(
                                1,
                                "原表正文",
                                json.write(List.of(evidence(11, "TABLE", 11, 102), evidence(11, "COLUMN", 12, 103))),
                                "a")));
        when(catalog.source(SOURCE, false)).thenReturn(source);
        when(catalog.current(source, 21)).thenReturn(table(21, "biz_b"));
        when(catalog.current(source, 11)).thenReturn(table(11, "biz_a"));
        when(documents.references(4L))
                .thenReturn(List.of(
                        new Reference(1, SOURCE, 11, 1, "TABLE", 11, 102, 11, true, "妥投线路"),
                        new Reference(1, SOURCE, 11, 1, "COLUMN", 12, 103, 12, true, "线路")));
        var table = GovernanceTarget.table(SOURCE, 11);
        var column = GovernanceTarget.column(SOURCE, 11, 12);
        when(governance.require(source, table))
                .thenReturn(
                        new GovernanceFactVO(table.view(), "102", 1, "11", "妥投线路", mapper.readTree("{}"), List.of()));
        when(governance.require(source, column))
                .thenReturn(new GovernanceFactVO(
                        column.view(), "103", 1, "12", "线路", mapper.readTree("{\"meaning\":\"线路\"}"), List.of()));
    }

    /**
     * 即使没有向量命中，也能从授权发布补到明确指定物理对象的正式依据。
     */
    @Test
    void findsAndCompilesOnlyTheRequestedPublishedTable() {
        var scope = corpus.targetedScope(SOURCE, List.of(BINDING), List.of(), "查biz_a.deliveries的线路", null);
        assertThat(scope.requiredObjects()).containsExactly("11");
        assertThat(scope.bases()).hasSize(1);
        var basis = scope.bases().get(0);
        assertThat(basis.text()).isEqualTo("原表正文");
        var plan = new Plan(
                "READY", null, "6", "VALUES", null, null, List.of(new Field("deliveries", "route")), List.of());
        var compiled = new QuerySql().compile(basis, plan, false, "logistics");
        assertThat(compiled.sql()).contains("FROM \"biz_a\".\"deliveries\"");
        verify(governance, never()).require(source, GovernanceTarget.table(SOURCE, 21));
    }

    /**
     * 冻结范围内找不到指定对象时，不能使用另一张同名表。
     */
    @Test
    void reportsKnowledgeGapAfterTheAuthorizedLookup() {
        assertThatThrownBy(() -> corpus.targetedScope(SOURCE, List.of(BINDING), List.of(), "查 biz_c.deliveries", null))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
        verify(governance, never()).require(any(), any());
    }

    /**
     * 补查仍按当前授权验证发布，撤权后不读取片段。
     */
    @Test
    void rechecksAuthorizationBeforeReadingSupplementaryEvidence() {
        when(versions.requireForQuery(SOURCE, BINDING)).thenThrow(BizIllegalException.denied());
        assertThatThrownBy(() -> corpus.targetedScope(SOURCE, List.of(BINDING), List.of(), "查 biz_a.deliveries", null))
                .isInstanceOf(BizIllegalException.class);
        verify(builds, never()).chunks("verified-build");
        verify(governance, never()).require(any(), any());
    }

    /**
     * 构造固定版本保存的对象级或字段级证据。
     */
    private Evidence evidence(long object, String kind, long target, long revision) {
        return new Evidence(
                Long.toString(object), 1, kind, Long.toString(target), Long.toString(revision), Long.toString(target));
    }

    /**
     * 模拟当前快照中能够核对身份和列修订的物理表。
     */
    private Entry table(long id, String schema) {
        var ref = new ObjectRef("logistics", schema, "deliveries", "TABLE", null, null, null);
        var structure = new Structure(ref, List.of(), List.of(), List.of(), List.of());
        var observation = new Observation(id, 1, 1L, id, null, null, null, structure, null);
        return new Entry(
                id,
                1,
                false,
                observation,
                List.of(new Column(id + 1, id + 1, "route", "character varying", false, "", 1)));
    }
}
