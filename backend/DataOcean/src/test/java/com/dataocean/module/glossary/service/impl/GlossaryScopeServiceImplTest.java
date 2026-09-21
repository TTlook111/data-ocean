package com.dataocean.module.glossary.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.service.GlossaryScopeService;
import com.dataocean.module.glossary.service.GlossaryScopeService.Scope;
import com.dataocean.module.glossary.service.GlossaryScopeService.ScopeStatus;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.mapper.MetadataEntityMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 5/修复轮聚焦测试：术语「源/全」混合范围的**三态**解析。
 *
 * <p>核心回归：空集合不能同时表示「未绑定」和「归属解析失败」。
 * 存在关联行但目标实体丢失或缺少 datasource_id 时必须是 BROKEN —— 按未绑定处理等于
 * 把「归属损坏」错误升级成「合法未绑定、全局放行」。</p>
 */
class GlossaryScopeServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 需要 TableInfo 才能解析列名
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                GlossaryTerm.class);
    }

    @Test
    void resolvesEveryTermsSourcesWithTwoQueriesRegardlessOfTermCount() {
        Fixture fixture = new Fixture();
        // 50 个术语，每个关联一个实体，分属数据源 5 / 6
        List<Long> termIds = new ArrayList<>();
        for (long termId = 1; termId <= 50; termId++) {
            termIds.add(termId);
            fixture.link(termId, 1000 + termId, termId % 2 == 0 ? 6L : 5L);
        }

        Map<Long, Scope> result = fixture.service.termScopes(termIds);

        assertThat(result).hasSize(50);
        assertThat(result.get(1L).status()).isEqualTo(ScopeStatus.BOUND);
        assertThat(result.get(1L).datasourceIds()).containsExactly(5L);
        assertThat(result.get(2L).datasourceIds()).containsExactly(6L);
        // 一次关系查询 + 一次实体批量查询，与术语数量无关
        verify(fixture.relationshipMapper, times(1)).selectGlossaryOfRelations(any());
        verify(fixture.entityMapper, times(1)).selectBatchIds(any());
    }

    @Test
    void termsWithoutAnyRelationAreUnbound() {
        Fixture fixture = new Fixture();
        fixture.link(1L, 1001L, 5L);

        Map<Long, Scope> result = fixture.service.termScopes(List.of(1L, 2L));

        assertThat(result.get(1L).status()).isEqualTo(ScopeStatus.BOUND);
        assertThat(result.get(2L).status()).isEqualTo(ScopeStatus.UNBOUND);
        assertThat(result.get(2L).datasourceIds()).isEmpty();
    }

    @Test
    void relationWhoseTargetEntityIsMissingIsBrokenNotUnbound() {
        Fixture fixture = new Fixture();
        // 关系存在，但目标实体在 metadata_entity 里查不到
        fixture.relationOnly(1L, 1001L);

        Scope scope = fixture.service.termScope(1L);

        assertThat(scope.status()).isEqualTo(ScopeStatus.BROKEN);
        assertThat(scope.isBroken()).isTrue();
    }

    @Test
    void relationWhoseEntityHasNoDatasourceIsBroken() {
        Fixture fixture = new Fixture();
        fixture.linkWithRawMetadata(1L, 1001L, "{}");

        assertThat(fixture.service.termScope(1L).status()).isEqualTo(ScopeStatus.BROKEN);
    }

    @Test
    void oneBrokenRelationMakesTheWholeTermBroken() {
        Fixture fixture = new Fixture();
        // 一条关联完整、另一条目标实体缺失：整个术语 BROKEN，不能只丢掉坏的那条
        fixture.link(1L, 1001L, 5L);
        fixture.relationOnly(1L, 1002L);

        assertThat(fixture.service.termScope(1L).status()).isEqualTo(ScopeStatus.BROKEN);
    }

    @Test
    void glossaryScopeIsBrokenWhenAnyTermIsBroken() {
        Fixture fixture = new Fixture();
        fixture.termsOfGlossary(9L, List.of(1L, 2L));
        fixture.link(1L, 1001L, 5L);
        fixture.relationOnly(2L, 1002L);

        // 否则“改术语表”会成为绕过该术语 409 的旁路
        assertThat(fixture.service.glossaryScope(9L).status()).isEqualTo(ScopeStatus.BROKEN);
    }

    @Test
    void glossaryScopeIsTheUnionOfItsTermsSources() {
        Fixture fixture = new Fixture();
        fixture.termsOfGlossary(9L, List.of(1L, 2L));
        fixture.link(1L, 1001L, 5L);
        fixture.link(2L, 1002L, 6L);

        Scope scope = fixture.service.glossaryScope(9L);

        assertThat(scope.status()).isEqualTo(ScopeStatus.BOUND);
        assertThat(scope.datasourceIds()).containsExactlyInAnyOrder(5L, 6L);
    }

    @Test
    void glossaryWithOnlyUnboundTermsIsUnbound() {
        Fixture fixture = new Fixture();
        fixture.termsOfGlossary(9L, List.of(1L, 2L));

        assertThat(fixture.service.glossaryScope(9L).status()).isEqualTo(ScopeStatus.UNBOUND);
    }

    @Test
    void writableScopeRejectsBrokenOutright() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.requireWritableScope(7L, "glossary:manage", Scope.broken()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
        // BROKEN 不是“无源可校验所以放行”
        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), any());
    }

    @Test
    void writableScopeOnUnboundOnlyChecksTheFunction() {
        Fixture fixture = new Fixture();

        fixture.service.requireWritableScope(7L, "glossary:manage", Scope.unbound());

        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), any());
    }

    @Test
    void writableScopeOnBoundChecksEverySourceAndFailsWhole() {
        Fixture fixture = new Fixture();
        doThrow(new BusinessException(403, "没有负责该数据源")).when(fixture.adminGuard)
                .requireDatasourceFunction(eq(7L), eq("glossary:manage"), eq(6L));

        assertThatThrownBy(() -> fixture.service.requireWritableScope(7L, "glossary:manage",
                Scope.bound(Set.of(5L, 6L))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(403));

        // 两个源都要校验，且没有任何写入发生（本方法只做判定）
        verify(fixture.adminGuard).requireDatasourceFunction(7L, "glossary:manage", 5L);
        verify(fixture.adminGuard).requireDatasourceFunction(7L, "glossary:manage", 6L);
    }

    @Test
    void visibilityTreatsBrokenAsInvisibleAndUnboundAsGlobal() {
        Set<Long> visible = Set.of(5L);

        assertThat(GlossaryScopeService.visibleIn(Scope.unbound(), Set.of())).isTrue();
        assertThat(GlossaryScopeService.visibleIn(Scope.broken(), visible)).isFalse();
        assertThat(GlossaryScopeService.visibleIn(Scope.bound(Set.of(6L)), visible)).isFalse();
        assertThat(GlossaryScopeService.visibleIn(Scope.bound(Set.of(5L, 6L)), visible)).isTrue();
        assertThat(GlossaryScopeService.visibleIn(null, visible)).isFalse();
    }

    @Test
    void visibleDatasourcesComeFromTheSameBindingCapabilityQuery() {
        Fixture fixture = new Fixture();
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "glossary:view"))
                .thenReturn(List.of(
                        new IamS1DatasourceRefVO(5L, "销售库", true),
                        new IamS1DatasourceRefVO(6L, "财务库", true)));

        assertThat(fixture.service.visibleDatasourceIds(7L, "glossary:view")).containsExactly(5L, 6L);
    }

    @Test
    void emptyInputsShortCircuitWithoutQuerying() {
        Fixture fixture = new Fixture();

        assertThat(fixture.service.termScope(null).status()).isEqualTo(ScopeStatus.UNBOUND);
        assertThat(fixture.service.termScopes(List.of())).isEmpty();
        assertThat(fixture.service.glossaryScope(null).status()).isEqualTo(ScopeStatus.UNBOUND);
        assertThat(fixture.service.entityDatasourceIds(List.of())).isEmpty();

        verify(fixture.relationshipMapper, never()).selectGlossaryOfRelations(any());
        verify(fixture.entityMapper, never()).selectBatchIds(any());
    }

    private static final class Fixture {
        private final IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final GlossaryTermMapper glossaryTermMapper = mock(GlossaryTermMapper.class);
        private final MetadataRelationshipMapper relationshipMapper = mock(MetadataRelationshipMapper.class);
        private final MetadataEntityMapper entityMapper = mock(MetadataEntityMapper.class);
        private final GlossaryScopeServiceImpl service = new GlossaryScopeServiceImpl(
                capabilityService, adminGuard, glossaryTermMapper, relationshipMapper, entityMapper);

        private final List<MetadataRelationship> relations = new ArrayList<>();
        private final List<MetadataEntity> entities = new ArrayList<>();

        Fixture() {
            when(relationshipMapper.selectGlossaryOfRelations(any())).thenAnswer(invocation -> {
                java.util.Collection<Long> termIds = invocation.getArgument(0);
                List<MetadataRelationship> rows = new ArrayList<>();
                for (MetadataRelationship relation : relations) {
                    if (termIds.contains(relation.getSourceId())) {
                        rows.add(relation);
                    }
                }
                return rows;
            });
            when(entityMapper.selectBatchIds(any())).thenAnswer(invocation -> {
                java.util.Collection<Long> entityIds = invocation.getArgument(0);
                List<MetadataEntity> rows = new ArrayList<>();
                for (MetadataEntity entity : entities) {
                    if (entityIds.contains(entity.getId())) {
                        rows.add(entity);
                    }
                }
                return rows;
            });
        }

        /** 术语关联某实体，且该实体归属指定数据源。 */
        void link(Long termId, Long entityId, Long datasourceId) {
            linkWithRawMetadata(termId, entityId, "{\"datasource_id\":" + datasourceId + "}");
        }

        void linkWithRawMetadata(Long termId, Long entityId, String metadata) {
            relationOnly(termId, entityId);
            MetadataEntity entity = new MetadataEntity();
            entity.setId(entityId);
            entity.setEntityType(MetadataEntity.TYPE_COLUMN);
            entity.setEntityMetadata(metadata);
            entities.add(entity);
        }

        /** 只建立关系、不给实体（模拟目标实体不存在）。 */
        void relationOnly(Long termId, Long entityId) {
            MetadataRelationship relation = new MetadataRelationship();
            relation.setSourceId(termId);
            relation.setSourceType("GLOSSARY_TERM");
            relation.setTargetId(entityId);
            relation.setRelationType(MetadataRelationship.TYPE_GLOSSARY_OF);
            relations.add(relation);
        }

        void termsOfGlossary(Long glossaryId, List<Long> termIds) {
            List<GlossaryTerm> terms = new ArrayList<>();
            for (Long termId : termIds) {
                GlossaryTerm term = new GlossaryTerm();
                term.setId(termId);
                term.setGlossaryId(glossaryId);
                terms.add(term);
            }
            when(glossaryTermMapper.selectList(any())).thenReturn(terms);
        }
    }
}
