package com.dataocean.module.permission.s1.aspect;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.glossary.controller.GlossaryController;
import com.dataocean.module.glossary.dto.TermLinkColumnDTO;
import com.dataocean.module.glossary.dto.TermReviewDTO;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.service.GlossaryScopeService;
import com.dataocean.module.glossary.service.GlossaryService;
import com.dataocean.module.glossary.service.GlossaryTermService;
import com.dataocean.module.glossary.service.impl.GlossaryScopeServiceImpl;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.mapper.MetadataEntityMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolverRegistry;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 5 聚焦测试：Glossary 的「源/全」混合范围拒绝必须发生在写入之前。
 *
 * <p>这里接**真实**的 {@link GlossaryScopeServiceImpl}（只 mock 它依赖的只读 Mapper 与
 * {@code IamS1AdminGuard}），而不是把范围服务整个 mock 掉——否则测的只是“控制器调用了某个方法”，
 * 证不了“逐源校验”这条规则本身。控制器仍用 {@link AspectJProxyFactory} 代理真实实例，
 * 保证注解确实生效（2026-09-20 的 P0 教训）。</p>
 */
class GlossaryControllerAuthorizationTest {

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // 真实 GlossaryScopeServiceImpl 用 LambdaQueryWrapper 读术语表下的术语，
        // 需要 TableInfo 才能解析列名
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.dataocean.module.glossary.entity.GlossaryTerm.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void multiSourceTermRejectsTheWholeWriteWhenOneSourceIsNotManaged() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        // 术语关联了两个源：5 有权、6 无权 → 写操作整体拒绝
        fixture.link(1L, 21L, 5L);
        fixture.link(1L, 22L, 6L);
        fixture.denyDatasource(7L, "glossary:manage", 6L);

        assertThatThrownBy(() -> fixture.controller.updateTerm(1L, new GlossaryTerm()))
                .isInstanceOf(BusinessException.class);

        verify(fixture.termService, never()).updateTerm(anyLong(), any());
    }

    @Test
    void unboundTermOnlyChecksTheFunction() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        // 未关联任何数据源：不推导任何数据源权限

        fixture.controller.updateTerm(1L, new GlossaryTerm());

        verify(fixture.termService).updateTerm(eq(1L), any());
        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), anyLong());
    }

    @Test
    void missingTermIsRejectedInsteadOfTreatedAsUnbound() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        // 术语不存在：必须 404，不能因为“查不到关联源”就按未关联放行
        when(fixture.termService.getById(404L)).thenReturn(null);

        assertThatThrownBy(() -> fixture.controller.deleteTerm(404L))
                .isInstanceOf(BusinessException.class);
        verify(fixture.termService, never()).deleteTerm(anyLong());
    }

    @Test
    void reviewUsesTheApproveCodeAndDoesNotImplyManage() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        fixture.link(1L, 21L, 5L);
        // 只有 glossary:approve：审核可用，维护仍被拒
        fixture.denyDatasource(7L, "glossary:manage", 5L);

        TermReviewDTO request = new TermReviewDTO();
        request.setApproved(true);
        fixture.controller.reviewTerm(1L, request);

        // TermReviewDTO.reason 默认是空串（不是 null），控制器原样透传
        verify(fixture.termService).reviewTerm(1L, 7L, true, "");
        // 审核不自动获得维护权：同一调用者不能改术语
        assertThatThrownBy(() -> fixture.controller.updateTerm(1L, new GlossaryTerm()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void linkColumnChecksBothTheTermSourcesAndTheTargetEntitySource() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        fixture.link(1L, 21L, 5L);
        fixture.entityExists(22L);
        // 目标实体 22 归属数据源 6，调用者不负责
        fixture.link(1L, 22L, 6L);
        fixture.denyDatasource(7L, "glossary:manage", 6L);

        TermLinkColumnDTO request = new TermLinkColumnDTO();
        request.setEntityId(22L);

        assertThatThrownBy(() -> fixture.controller.linkTermToColumn(1L, request))
                .isInstanceOf(BusinessException.class);

        // 两侧都通过前不得建立关系
        verify(fixture.relationshipService, never()).upsert(any());
    }

    @Test
    void linkColumnRejectsWhenTheTargetEntityHasNoResolvableOwnership() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        // 实体存在但元数据里没有数据源归属 → fail-closed，而不是当成“没有源限制”
        fixture.entityExists(22L);

        TermLinkColumnDTO request = new TermLinkColumnDTO();
        request.setEntityId(22L);

        assertThatThrownBy(() -> fixture.controller.linkTermToColumn(1L, request))
                .isInstanceOf(BusinessException.class);
        verify(fixture.relationshipService, never()).upsert(any());
    }

    @Test
    void linkedColumnsOnlyReturnEntitiesInsideTheResponsibleSources() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        fixture.visibleDatasources(7L, "glossary:view", 5L);
        fixture.entityExists(21L);
        fixture.entityExists(22L);
        // 术语关联 21（源 5，可见）与 22（源 6，不可见）
        fixture.link(1L, 21L, 5L);
        fixture.link(1L, 22L, 6L);

        List<MetadataEntity> result = fixture.controller.getLinkedColumns(1L).getData();

        // 只返回负责源内的关联字段；无权源的实体 ID 与 FQN 一律不返回
        assertThat(result).extracting(MetadataEntity::getId).containsExactly(21L);
    }

    @Test
    void brokenTermIsNotReturnedInTheTermList() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        fixture.visibleDatasources(7L, "glossary:view", 5L);
        when(fixture.termService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(fixture.termOf(1L, "orders")));
        // 关系存在但目标实体查不到 → BROKEN：不能因为解析不出源就当成“合法未绑定”返回
        fixture.brokenLink(1L, 21L);

        List<GlossaryTerm> terms = fixture.controller.listTerms(9L, null).getData();

        assertThat(terms).isEmpty();
    }

    @Test
    void brokenTermRejectsUpdateReviewAndDelete() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        fixture.brokenLink(1L, 21L);

        assertThatThrownBy(() -> fixture.controller.updateTerm(1L, new GlossaryTerm()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
        TermReviewDTO review = new TermReviewDTO();
        review.setApproved(true);
        assertThatThrownBy(() -> fixture.controller.reviewTerm(1L, review))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
        assertThatThrownBy(() -> fixture.controller.deleteTerm(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));

        verify(fixture.termService, never()).updateTerm(anyLong(), any());
        verify(fixture.termService, never()).reviewTerm(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(fixture.termService, never()).deleteTerm(anyLong());
    }

    @Test
    void brokenTermMakesTheWholeGlossaryUnwritable() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.glossaryExists(9L);
        fixture.termsOfGlossary(9L, List.of(1L));
        // 术语表本身没变，但它下面有一个归属损坏的术语：改/删术语表不能成为绕过该术语 409 的旁路
        fixture.brokenLink(1L, 21L);

        assertThatThrownBy(() -> fixture.controller.updateGlossary(9L, new Glossary()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
        assertThatThrownBy(() -> fixture.controller.deleteGlossary(9L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));

        verify(fixture.glossaryService, never()).updateById(any());
        verify(fixture.glossaryService, never()).deleteGlossary(anyLong());
    }

    @Test
    void brokenTermDoesNotReturnLinkedColumnsAsAnEmptyList() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        fixture.brokenLink(1L, 21L);

        // 返回空列表会把「关联已损坏」伪装成「这个术语本来就没有关联字段」
        assertThatThrownBy(() -> fixture.controller.getLinkedColumns(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void linkColumnRejectsNonColumnEntities() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.termExists(1L, "orders");
        fixture.entityExistsWithType(22L, MetadataEntity.TYPE_TABLE);

        TermLinkColumnDTO request = new TermLinkColumnDTO();
        request.setEntityId(22L);

        // 术语只能关联物理列：表/库/数据源实体都能建 GLOSSARY_OF 会稀释“术语 → 字段”的语义
        assertThat(fixture.controller.linkTermToColumn(1L, request).getCode()).isEqualTo(400);
        verify(fixture.relationshipService, never()).upsert(any());
    }
    @Test
    void visibleInTreatsUnboundAsGlobalAndBrokenAsInvisible() {
        // 纯函数规则：未关联任何源 → 按全局语义可见；归属损坏 → 不可见
        assertThat(GlossaryScopeService.visibleIn(GlossaryScopeService.Scope.unbound(), Set.of())).isTrue();
        assertThat(GlossaryScopeService.visibleIn(GlossaryScopeService.Scope.broken(), Set.of(5L))).isFalse();
        // 已关联 → 至少要有一个可见源
        assertThat(GlossaryScopeService.visibleIn(
                GlossaryScopeService.Scope.bound(Set.of(6L)), Set.of(5L))).isFalse();
        assertThat(GlossaryScopeService.visibleIn(
                GlossaryScopeService.Scope.bound(Set.of(5L, 6L)), Set.of(5L))).isTrue();
    }

    @Test
    void notLoggedInNeverReachesTheService() {
        Fixture fixture = new Fixture();
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> fixture.controller.listGlossaries())
                .isInstanceOf(BusinessException.class);
        verify(fixture.glossaryService, never())
                .list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    // ---------- 夹具 ----------

    private static final class Fixture {
        private final GlossaryService glossaryService = mock(GlossaryService.class);
        private final GlossaryTermService termService = mock(GlossaryTermService.class);
        private final MetadataEntityService entityService = mock(MetadataEntityService.class);
        private final MetadataRelationshipService relationshipService = mock(MetadataRelationshipService.class);
        private final IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final GlossaryTermMapper glossaryTermMapper = mock(GlossaryTermMapper.class);
        private final MetadataRelationshipMapper relationshipMapper = mock(MetadataRelationshipMapper.class);
        private final MetadataEntityMapper entityMapper = mock(MetadataEntityMapper.class);
        private final GlossaryController controller;

        /** 术语 → 实体 → 数据源 的种子关联，供真实范围服务的只读 Mapper 应答。 */
        private final List<long[]> links = new ArrayList<>();
        /** 只建立关系、不参与归属解析的实体（用于“归属解析不出来”的用例）。 */
        private final List<long[]> relations = new ArrayList<>();

        Fixture() {
            GlossaryScopeService scopeService = new GlossaryScopeServiceImpl(
                    capabilityService, adminGuard, glossaryTermMapper, relationshipMapper, entityMapper);
            GlossaryController target = new GlossaryController(glossaryService, termService, scopeService,
                    entityService, relationshipService);
            IamS1AuthorizationAspect aspect = new IamS1AuthorizationAspect(adminGuard,
                    new IamS1ResourceResolverRegistry(List.of()));
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            this.controller = factory.getProxy();
            // 只在这里注册一次只读应答：`when(mock.method(any()))` 本身会调用 mock，
            // 重复注册会让上一次的应答以 `any()` 的 null 实参被触发。
            stubReads();
        }

        GlossaryTerm termOf(Long termId, String name) {
            GlossaryTerm term = new GlossaryTerm();
            term.setId(termId);
            term.setName(name);
            term.setGlossaryId(9L);
            return term;
        }

        void glossaryExists(Long glossaryId) {
            Glossary glossary = new Glossary();
            glossary.setId(glossaryId);
            when(glossaryService.getById(glossaryId)).thenReturn(glossary);
        }

        void termsOfGlossary(Long glossaryId, List<Long> termIds) {
            List<GlossaryTerm> terms = new ArrayList<>();
            for (Long termId : termIds) {
                terms.add(termOf(termId, "term-" + termId));
            }
            when(glossaryTermMapper.selectList(any())).thenReturn(terms);
        }

        /** 只建立关系、不给实体：模拟目标实体不存在导致的归属损坏。 */
        void brokenLink(Long termId, Long entityId) {
            relations.add(new long[]{termId, entityId});
        }

        void entityExistsWithType(Long entityId, String entityType) {
            MetadataEntity entity = new MetadataEntity();
            entity.setId(entityId);
            entity.setEntityType(entityType);
            when(entityService.getById(entityId)).thenReturn(entity);
        }

        void termExists(Long termId, String name) {
            GlossaryTerm term = new GlossaryTerm();
            term.setId(termId);
            term.setName(name);
            when(termService.getById(termId)).thenReturn(term);
        }

        void entityExists(Long entityId) {
            MetadataEntity entity = new MetadataEntity();
            entity.setId(entityId);
            entity.setEntityType(MetadataEntity.TYPE_COLUMN);
            when(entityService.getById(entityId)).thenReturn(entity);
        }

        /** 术语关联某实体，且该实体归属指定数据源。 */
        void link(Long termId, Long entityId, Long datasourceId) {
            links.add(new long[]{termId, entityId, datasourceId});
            relations.add(new long[]{termId, entityId});
        }

        /** 只建立 GLOSSARY_OF 关系，不给实体归属（模拟归属断链）。 */
        void relationExists(Long termId, Long entityId) {
            relations.add(new long[]{termId, entityId});
        }

        void visibleDatasources(Long userId, String functionCode, Long... datasourceIds) {
            List<IamS1DatasourceRefVO> refs = new ArrayList<>();
            for (Long id : datasourceIds) {
                refs.add(new IamS1DatasourceRefVO(id, "源" + id, true));
            }
            when(capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)).thenReturn(refs);
        }

        private void stubReads() {
            // 一次批量关系查询：按请求的术语集合过滤
            when(relationshipMapper.selectGlossaryOfRelations(any())).thenAnswer(invocation -> {
                Collection<Long> termIds = invocation.getArgument(0);
                List<MetadataRelationship> rows = new ArrayList<>();
                for (long[] relation : relations) {
                    if (termIds.contains(relation[0])) {
                        rows.add(relation(relation[0], relation[1]));
                    }
                }
                return rows;
            });
            // 一次批量实体查询：只有种子里的实体带数据源归属
            when(entityMapper.selectBatchIds(any())).thenAnswer(invocation -> {
                Collection<Long> entityIds = invocation.getArgument(0);
                List<MetadataEntity> rows = new ArrayList<>();
                for (long[] link : links) {
                    if (entityIds.contains(link[1])) {
                        MetadataEntity entity = new MetadataEntity();
                        entity.setId(link[1]);
                        entity.setEntityType(MetadataEntity.TYPE_COLUMN);
                        entity.setFqn("ds.db.t.c" + link[1]);
                        entity.setEntityMetadata("{\"datasource_id\":" + link[2] + "}");
                        rows.add(entity);
                    }
                }
                return rows;
            });
            // 控制器读取某术语的关联关系走的是关系 Service（不是上面的只读 Mapper）
            when(relationshipService.getBySource(anyLong(), anyString())).thenAnswer(invocation -> {
                long termId = ((Long) invocation.getArgument(0));
                List<MetadataRelationship> rows = new ArrayList<>();
                for (long[] relation : relations) {
                    if (relation[0] == termId) {
                        rows.add(relation(relation[0], relation[1]));
                    }
                }
                return rows;
            });
        }

        private static MetadataRelationship relation(Long termId, Long entityId) {
            MetadataRelationship relation = new MetadataRelationship();
            relation.setSourceId(termId);
            relation.setSourceType("GLOSSARY_TERM");
            relation.setTargetId(entityId);
            relation.setTargetType(MetadataEntity.TYPE_COLUMN);
            relation.setRelationType(MetadataRelationship.TYPE_GLOSSARY_OF);
            return relation;
        }

        void denyDatasource(Long userId, String functionCode, Long datasourceId) {
            doThrow(new BusinessException(403, "没有负责该数据源")).when(adminGuard)
                    .requireDatasourceFunction(eq(userId), eq(functionCode), eq(datasourceId));
        }

        void login(Long userId) {
            LoginUser loginUser = new LoginUser(userId, "tester", "password", "测试员",
                    List.of(), List.of(), List.of());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
        }
    }
}
