package com.dataocean.module.permission.s1.aspect;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.governance.controller.MetadataGovernanceController;
import com.dataocean.module.governance.service.GovernanceStatusService;
import com.dataocean.module.governance.service.MetadataReviewService;
import com.dataocean.module.governance.service.QualityCheckService;
import com.dataocean.module.governance.service.QualityIssueService;
import com.dataocean.module.governance.service.QualityRuleService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolverRegistry;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 批次 4 聚焦测试：注解声明的准入必须**真的执行**，且在 Service 之前执行。
 *
 * <p>为什么不能只测 {@code IamS1AuthorizationAspect#checkResource} 的行为：
 * 那样只证明“切面被调用时是对的”，证明不了它会被调用。2026-09-20 复审发现
 * {@code IamS1AuthorizationAspect} 只有 {@code @Component} 没有 {@code @Aspect}，
 * Spring AOP 因此完全不代理这些端点——而迁移时旧 {@code @PreAuthorize} 已被删除，
 * {@code /api/admin/**} 在 SecurityConfig 里只要求 {@code authenticated()}。
 * 也就是说 12 个治理端点当时对任何已登录用户开放，而所有既有测试都是绿色。</p>
 *
 * <p>本测试用 {@link AspectJProxyFactory} 代理**真实的 Controller**，断言拒绝时
 * Service 一次都没被调用，把“注解存在”与“注解生效”之间的缺口补上。</p>
 */
class MetadataGovernanceControllerAuthorizationTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theAspectIsAClassSpringAopWillActuallyApply() {
        // 缺 @Aspect 时 AspectJProxyFactory 直接抛 IllegalArgumentException，
        // 但显式断言能在失败时给出比“某个代理构造异常”更准确的定位。
        assertThat(IamS1AuthorizationAspect.class.isAnnotationPresent(Aspect.class))
                .as("IamS1AuthorizationAspect 必须声明 @Aspect，否则所有 @IamS1* 注解都是装饰")
                .isTrue();
    }

    @Test
    void qualityCheckIsRejectedBeforeTheServiceIsCalled() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.snapshotOwnedBy(8L, 5L);
        // 调用者不负责该快照的数据源
        doThrow(new BusinessException(403, "没有负责该数据源"))
                .when(fixture.adminGuard).requireDatasourceFunction(7L, "governance:check", 5L);

        assertThatThrownBy(() -> fixture.controller.triggerQualityCheck(8L, null))
                .isInstanceOf(BusinessException.class);

        // 拒绝必须发生在业务之前：不创建校验任务、不产生问题、不触发任何异步副作用
        verify(fixture.qualityCheckService, never())
                .executeQualityCheck(anyLong(), any(), any());
    }

    @Test
    void qualityCheckReachesTheServiceWhenTheCallerIsResponsible() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.snapshotOwnedBy(8L, 5L);

        fixture.controller.triggerQualityCheck(8L, null);

        verify(fixture.adminGuard).requireDatasourceFunction(7L, "governance:check", 5L);
        verify(fixture.qualityCheckService).executeQualityCheck(eq(8L), any(), any());
    }

    @Test
    void scopedListEndpointsRequireTheFunctionButNotAResponsibleDatasource() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        // 一个负责源都没有：这是合法的空页，不是准入失败
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "governance:issue:view"))
                .thenReturn(List.of());
        when(fixture.qualityIssueService.listIssuesInDatasources(
                any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        fixture.controller.listAllIssues(null, null, null, null, null, null, 1, 20);

        verify(fixture.adminGuard).requireGlobalFunction(7L, "governance:issue:view");
        // 范围由 Service 下推 SQL，注解层不解析资源
        verify(fixture.qualityIssueService).listIssuesInDatasources(
                eq(List.of()), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notLoggedInNeverReachesTheService() {
        Fixture fixture = new Fixture();
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> fixture.controller.triggerQualityCheck(8L, null))
                .isInstanceOf(BusinessException.class);

        verify(fixture.qualityCheckService, never()).executeQualityCheck(anyLong(), any(), any());
    }

    // ---------- 夹具 ----------

    private static final class Fixture {
        private final QualityCheckService qualityCheckService = mock(QualityCheckService.class);
        private final QualityIssueService qualityIssueService = mock(QualityIssueService.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);
        private final IamS1ResourceResolver snapshotResolver = mock(IamS1ResourceResolver.class);
        private final MetadataGovernanceController controller;

        Fixture() {
            MetadataGovernanceController target = new MetadataGovernanceController(
                    qualityCheckService,
                    mock(QualityRuleService.class),
                    qualityIssueService,
                    mock(GovernanceStatusService.class),
                    mock(MetadataReviewService.class),
                    capabilityService);
            when(snapshotResolver.supports()).thenReturn(IamS1ResourceType.SNAPSHOT);
            IamS1ResourceResolverRegistry registry = new IamS1ResourceResolverRegistry(List.of(snapshotResolver));
            IamS1AuthorizationAspect aspect = new IamS1AuthorizationAspect(adminGuard, registry);

            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            this.controller = factory.getProxy();
        }

        void login(Long userId) {
            LoginUser loginUser = new LoginUser(userId, "tester", "password", "测试员",
                    List.of(), List.of(), List.of());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
        }

        void snapshotOwnedBy(Long snapshotId, Long datasourceId) {
            when(snapshotResolver.resolve(snapshotId)).thenReturn(IamS1ResolvedResource.of(
                    IamS1ResourceType.SNAPSHOT, snapshotId, datasourceId));
        }
    }
}
