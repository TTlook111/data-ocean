package com.dataocean.module.permission.s1.aspect;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.knowledge.controller.KnowledgeDocController;
import com.dataocean.module.knowledge.dto.BatchGenerateDTO;
import com.dataocean.module.knowledge.dto.RollbackDTO;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.knowledge.service.impl.KnowledgeDocCrudService;
import com.dataocean.module.knowledge.service.impl.KnowledgeDocLifecycleService;
import com.dataocean.module.knowledge.service.impl.KnowledgeDocPublishService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolverRegistry;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 5 聚焦测试：知识文档的四个功能码与文档归属复核必须真的在代理层生效。
 *
 * <p>重点断言“拒绝发生在业务之前”：无权时不得调用 Service，也就不会触发
 * Python 调用、不会创建向量任务、不会修改生命周期状态。</p>
 */
class KnowledgeDocControllerAuthorizationTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishIsRejectedBeforeTheLifecycleServiceIsCalled() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.documentOwnedBy(11L, 5L);
        fixture.denyDatasource(7L, "knowledge:publish", 5L);

        assertThatThrownBy(() -> fixture.controller.publish(11L))
                .isInstanceOf(BusinessException.class);

        verify(fixture.lifecycleService, never()).publish(anyLong());
    }

    @Test
    void publishReachesTheLifecycleServiceWhenGranted() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.documentOwnedBy(11L, 5L);

        fixture.controller.publish(11L);

        verify(fixture.adminGuard).requireDatasourceFunction(7L, "knowledge:publish", 5L);
        verify(fixture.lifecycleService).publish(11L);
    }

    @Test
    void approveAndPublishAreIndependentCodes() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.documentOwnedBy(11L, 5L);
        // 只有发布权：审核动作必须被拒绝
        fixture.denyDatasource(7L, "knowledge:approve", 5L);

        assertThatThrownBy(() -> fixture.controller.approve(11L, null))
                .isInstanceOf(BusinessException.class);
        verify(fixture.lifecycleService, never()).approve(anyLong(), any());
    }

    @Test
    void generateFromSnapshotChecksTheRealSnapshotOwnership() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        // 快照 8 真实归属数据源 6；调用者不负责该源
        fixture.snapshotOwnedBy(8L, 6L);
        fixture.denyDatasource(7L, "knowledge:manage", 6L);

        BatchGenerateDTO request = new BatchGenerateDTO();
        request.setSnapshotId(8L);

        assertThatThrownBy(() -> fixture.controller.generateFromSnapshot(5L, request))
                .isInstanceOf(BusinessException.class);

        // 无权时不得调用 Service —— 也就不会有 Python 调用，更不会创建任何文档
        verify(fixture.publishService, never()).batchGenerateFromSnapshot(anyLong(), anyLong());
    }

    @Test
    void generateFromSnapshotReachesTheServiceWhenGranted() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.snapshotOwnedBy(8L, 5L);

        BatchGenerateDTO request = new BatchGenerateDTO();
        request.setSnapshotId(8L);

        fixture.controller.generateFromSnapshot(5L, request);

        verify(fixture.publishService).batchGenerateFromSnapshot(5L, 8L);
    }

    @Test
    void readOnlyPreviewChunksUsesTheViewCode() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.documentOwnedBy(11L, 5L);
        // 只有查看权：切片预览可用，且不得要求 manage
        fixture.denyDatasource(7L, "knowledge:manage", 5L);

        fixture.controller.previewChunks(11L);

        verify(fixture.publishService).previewChunks(11L);
        verify(fixture.publishService, never()).generateDraft(anyLong(), anyLong());
    }

    @Test
    void listDocsPushesTheResponsibleScopeIntoTheQuery() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "knowledge:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(5L, "销售库", true)));

        fixture.controller.listDocs(null, null, 1, 10);

        verify(fixture.crudService).listDocsInDatasources(eq(List.of(5L)), any(), any(), anyInt(), anyInt());
    }

    @Test
    void listDocsDoesNotQueryWhenThereIsNoResponsibleDatasource() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "knowledge:view"))
                .thenReturn(List.of());
        when(fixture.crudService.listDocsInDatasources(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        fixture.controller.listDocs(null, null, 1, 10);

        // 空负责源：仍然调用 Service，但传的是空集合，由 Service 返回空页而不是全局结果
        verify(fixture.crudService).listDocsInDatasources(eq(List.of()), any(), any(), anyInt(), anyInt());
    }

    @Test
    void rollbackIsRejectedBeforeTheVersionServiceIsCalled() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.documentOwnedBy(11L, 5L);
        fixture.denyDatasource(7L, "knowledge:publish", 5L);

        RollbackDTO request = new RollbackDTO();
        request.setTargetVersionNo(1);

        assertThatThrownBy(() -> fixture.controller.rollback(11L, request))
                .isInstanceOf(BusinessException.class);
        verify(fixture.versionService, never()).rollback(anyLong(), anyInt());
    }

    // ---------- 夹具 ----------

    private static final class Fixture {
        private final KnowledgeDocCrudService crudService = mock(KnowledgeDocCrudService.class);
        private final KnowledgeDocLifecycleService lifecycleService = mock(KnowledgeDocLifecycleService.class);
        private final KnowledgeDocPublishService publishService = mock(KnowledgeDocPublishService.class);
        private final KnowledgeVersionService versionService = mock(KnowledgeVersionService.class);
        private final VectorIndexTaskService vectorIndexTaskService = mock(VectorIndexTaskService.class);
        private final IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1ResourceResolver documentResolver = mock(IamS1ResourceResolver.class);
        private final IamS1ResourceResolver snapshotResolver = mock(IamS1ResourceResolver.class);
        private final KnowledgeDocController controller;

        Fixture() {
            KnowledgeDocController target = new KnowledgeDocController(crudService, lifecycleService,
                    publishService, versionService, vectorIndexTaskService, capabilityService);
            when(documentResolver.supports()).thenReturn(IamS1ResourceType.KNOWLEDGE_DOCUMENT);
            when(snapshotResolver.supports()).thenReturn(IamS1ResourceType.SNAPSHOT);
            IamS1ResourceResolverRegistry registry =
                    new IamS1ResourceResolverRegistry(List.of(documentResolver, snapshotResolver));
            IamS1AuthorizationAspect aspect = new IamS1AuthorizationAspect(adminGuard, registry);

            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            this.controller = factory.getProxy();
        }

        void documentOwnedBy(Long documentId, Long datasourceId) {
            when(documentResolver.resolve(documentId)).thenReturn(IamS1ResolvedResource.of(
                    IamS1ResourceType.KNOWLEDGE_DOCUMENT, documentId, datasourceId));
        }

        void snapshotOwnedBy(Long snapshotId, Long datasourceId) {
            when(snapshotResolver.resolve(snapshotId)).thenReturn(IamS1ResolvedResource.of(
                    IamS1ResourceType.SNAPSHOT, snapshotId, datasourceId));
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
