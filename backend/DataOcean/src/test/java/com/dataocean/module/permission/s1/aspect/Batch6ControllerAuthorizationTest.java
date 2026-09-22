package com.dataocean.module.permission.s1.aspect;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.audit.controller.AlertController;
import com.dataocean.module.audit.controller.AuditLogController;
import com.dataocean.module.audit.controller.LineageController;
import com.dataocean.module.audit.entity.dto.AuditLogQueryDTO;
import com.dataocean.module.audit.service.AlertRuleService;
import com.dataocean.module.audit.service.AuditLogService;
import com.dataocean.module.audit.service.LineageService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolverRegistry;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.system.controller.AiConfigController;
import com.dataocean.module.system.controller.SyncScheduleController;
import com.dataocean.module.system.entity.dto.SyncScheduleDTO;
import com.dataocean.module.system.service.SysConfigService;
import com.dataocean.module.user.controller.DepartmentController;
import com.dataocean.module.user.controller.UserController;
import com.dataocean.module.user.entity.dto.DepartmentCreateDTO;
import com.dataocean.module.user.service.DepartmentService;
import com.dataocean.module.user.service.UserService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6 聚焦测试：组织基础数据 + 运营与平台 + 遗漏治理入口的准入必须**真的在代理层生效**。
 *
 * <p>每个用例都通过 {@link AspectJProxyFactory} 代理**真实 Controller**，
 * 断言拒绝发生在业务之前（Service 零调用）。直接调用 {@code aspect.checkXxx()} 只能证明切面被调用时是对的，
 * 证明不了它会被调用——2026-09-20 的 P0 正是栽在这一点上。</p>
 *
 * <p>「所有已迁移 Controller（含批次 6 的 14 个）都是真实 AOP 代理」由
 * {@code IamS1EndpointCoverageTest#migratedControllersAreActuallyProxiedSoTheAnnotationsRun}
 * 在真实容器里统一断言。</p>
 */
class Batch6ControllerAuthorizationTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 审计查询 ----------

    @Test
    void auditListIsRejectedBeforeTheServiceWhenAuditViewIsMissing() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.denyGlobal(7L, "audit:view");

        assertThatThrownBy(() -> fixture.auditLogController.listAuditLogs(new AuditLogQueryDTO()))
                .isInstanceOf(BusinessException.class);

        verify(fixture.auditLogService, never()).listAuditLogsInDatasources(any(), any());
    }

    @Test
    void auditListReachesTheServiceWithTheResponsibleScope() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "audit:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(5L, "销售库", true)));

        fixture.auditLogController.listAuditLogs(new AuditLogQueryDTO());

        verify(fixture.auditLogService).listAuditLogsInDatasources(any(), eq(List.of(5L)));
    }

    // ---------- 血缘 ----------

    @Test
    void lineageQueryIsRejectedOnAnUnmanagedDatasource() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.datasourceOwnedBy(5L, 5L);
        fixture.denyDatasource(7L, "lineage:view", 5L);

        assertThatThrownBy(() -> fixture.lineageController.queryTableLineage(5L, "orders"))
                .isInstanceOf(BusinessException.class);

        verify(fixture.lineageService, never()).queryTableLineage(anyLong(), anyString());
    }

    // ---------- 运行监控 ----------

    @Test
    void alertCreateNeedsRuntimeManageAndNeverReachesTheService() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.denyGlobal(7L, "system:runtime:manage");

        assertThatThrownBy(() -> fixture.alertController.createRule(null))
                .isInstanceOf(BusinessException.class);
        verify(fixture.alertRuleService, never()).createRule(any());
    }

    @Test
    void alertListOnlyNeedsRuntimeView() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        // 只有查看权：列表可用，写操作仍被拒
        fixture.denyGlobal(7L, "system:runtime:manage");

        fixture.alertController.listRules(1, 20);
        verify(fixture.alertRuleService).listRules(1, 20);
    }

    // ---------- 全局采集计划 ----------

    @Test
    void globalScheduleWriteIsRejectedForNonSystemAdmin() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        when(fixture.adminGuard.isSystemAdmin(7L)).thenReturn(false);
        SyncScheduleDTO dto = new SyncScheduleDTO();
        dto.setCron("0 0 3 * * ?");
        dto.setEnabled(true);

        // 计划是全局的：只负责一个数据源的人不能改变所有数据源的采集计划
        assertThatThrownBy(() -> fixture.syncScheduleController.updateSchedule(dto))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(403));
        verify(fixture.configService, never()).setValue(anyString(), anyString());
    }

    // ---------- 组织基础数据 ----------

    @Test
    void userExportUsesItsOwnCodeNotTheViewCode() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        // 有查看权但没有导出权
        fixture.denyGlobal(7L, "organization:user:export");

        assertThatThrownBy(() -> fixture.userController.exportUsers(
                new com.dataocean.module.user.entity.query.UserQuery(), null))
                .isInstanceOf(BusinessException.class);
        verify(fixture.userService, never()).listUsers(any());
    }

    @Test
    void userListOnlyNeedsView() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.denyGlobal(7L, "organization:user:manage");

        fixture.userController.listUsers(new com.dataocean.module.user.entity.query.UserQuery());
        verify(fixture.userService).listUsers(any());
    }

    @Test
    void departmentWriteNeedsManageAndNeverReachesTheService() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.denyGlobal(7L, "organization:department:manage");

        assertThatThrownBy(() -> fixture.departmentController.createDepartment(new DepartmentCreateDTO()))
                .isInstanceOf(BusinessException.class);
        verify(fixture.departmentService, never()).createDepartment(any());
    }

    @Test
    void departmentTreeOnlyNeedsView() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.denyGlobal(7L, "organization:department:manage");

        fixture.departmentController.tree();
        verify(fixture.departmentService).tree();
    }

    // ---------- AI 配置 ----------

    @Test
    void aiConfigViewAndManageAreIndependent() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.denyGlobal(7L, "system:ai-config:manage");

        // 查看可用
        fixture.aiConfigController.getConfig();
        verify(fixture.aiConfigService).getConfig();

        // 维护仍被拒，且不触达 Service
        assertThatThrownBy(() -> fixture.aiConfigController.updateConfig(null))
                .isInstanceOf(BusinessException.class);
        verify(fixture.aiConfigService, never()).updateConfig(any());
    }

    @Test
    void notLoggedInNeverReachesAnyService() {
        Fixture fixture = new Fixture();
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> fixture.auditLogController.listAuditLogs(new AuditLogQueryDTO()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.alertController.listRules(1, 20))
                .isInstanceOf(BusinessException.class);
        verify(fixture.auditLogService, never()).listAuditLogsInDatasources(any(), any());
        verify(fixture.alertRuleService, never()).listRules(anyInt(), anyInt());
    }

    // ---------- 夹具 ----------

    private static final class Fixture {
        private final AuditLogService auditLogService = mock(AuditLogService.class);
        private final IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);
        private final LineageService lineageService = mock(LineageService.class);
        private final AlertRuleService alertRuleService = mock(AlertRuleService.class);
        private final SysConfigService configService = mock(SysConfigService.class);
        private final UserService userService = mock(UserService.class);
        private final DepartmentService departmentService = mock(DepartmentService.class);
        private final com.dataocean.module.system.service.AiConfigService aiConfigService =
                mock(com.dataocean.module.system.service.AiConfigService.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1ResourceResolver datasourceResolver = mock(IamS1ResourceResolver.class);

        private final AuditLogController auditLogController;
        private final LineageController lineageController;
        private final AlertController alertController;
        private final SyncScheduleController syncScheduleController;
        private final UserController userController;
        private final DepartmentController departmentController;
        private final AiConfigController aiConfigController;

        Fixture() {
            when(datasourceResolver.supports()).thenReturn(IamS1ResourceType.DATASOURCE);
            IamS1ResourceResolverRegistry registry =
                    new IamS1ResourceResolverRegistry(List.of(datasourceResolver));
            IamS1AuthorizationAspect aspect = new IamS1AuthorizationAspect(adminGuard, registry);

            this.auditLogController = proxy(new AuditLogController(auditLogService, capabilityService), aspect);
            this.lineageController = proxy(new LineageController(lineageService), aspect);
            this.alertController = proxy(new AlertController(alertRuleService), aspect);
            this.syncScheduleController = proxy(
                    new SyncScheduleController(configService, adminGuard,
                            mock(com.dataocean.module.metadata.scheduler.AutoSyncScheduler.class)), aspect);
            this.userController = proxy(new UserController(userService), aspect);
            this.departmentController = proxy(new DepartmentController(departmentService), aspect);
            this.aiConfigController = proxy(new AiConfigController(aiConfigService,
                    mock(com.dataocean.module.system.client.PythonAiConfigClient.class)), aspect);
        }

        @SuppressWarnings("unchecked")
        private <T> T proxy(T target, IamS1AuthorizationAspect aspect) {
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            return factory.getProxy();
        }

        void datasourceOwnedBy(Long datasourceId, Long realDatasourceId) {
            when(datasourceResolver.resolve(datasourceId)).thenReturn(IamS1ResolvedResource.of(
                    IamS1ResourceType.DATASOURCE, datasourceId, realDatasourceId));
        }

        void denyGlobal(Long userId, String functionCode) {
            doThrow(new BusinessException(403, "没有该功能")).when(adminGuard)
                    .requireGlobalFunction(eq(userId), eq(functionCode));
        }

        void denyDatasource(Long userId, String functionCode, Long datasourceId) {
            doThrow(new BusinessException(403, "没有负责该数据源")).when(adminGuard)
                    .requireDatasourceFunction(eq(userId), eq(functionCode), eq(datasourceId));
        }

        void login(Long userId) {
            LoginUser loginUser = new LoginUser(userId, "tester", "password", "测试员", List.of());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
        }
    }
}
