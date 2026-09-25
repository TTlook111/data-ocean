package com.dataocean.module.permission.s1.aspect;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.prompt.controller.PromptTemplateController;
import com.dataocean.module.prompt.entity.dto.PromptTemplateUpdateDTO;
import com.dataocean.module.prompt.entity.dto.PromptTemplateEnabledDTO;
import com.dataocean.module.prompt.service.PromptTemplateService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 批次 5 聚焦测试：Prompt 三个功能码相互独立，且注解必须**真的生效**。
 *
 * <p>2026-09-20 的 P0（切面缺 `@Aspect`）说明：只断言“注解存在”证明不了任何事。
 * 这里用 {@link AspectJProxyFactory} 代理**真实 Controller**，断言拒绝时 Service 零调用。</p>
 */
class PromptTemplateControllerAuthorizationTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateIsRejectedBeforeTheServiceIsCalledWhenManageIsMissing() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        fixture.denyGlobal(7L, "prompt:manage");

        assertThatThrownBy(() -> fixture.controller.update("SQL_GEN", updateRequest()))
                .isInstanceOf(BusinessException.class);

        // 拒绝必须发生在业务之前：不写模板、不产生新版本、不触发任何后续动作
        verify(fixture.service, never()).updateTemplate(anyString(), any());
    }

    @Test
    void updateReachesTheServiceWhenManageIsGranted() {
        Fixture fixture = new Fixture();
        fixture.login(7L);

        fixture.controller.update("SQL_GEN", updateRequest());

        verify(fixture.service).updateTemplate(eq("SQL_GEN"), any());
    }

    @Test
    void approveRightsDoNotImplyManageRights() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        // 只有 prompt:approve：审批不自动获得维护权
        fixture.denyGlobal(7L, "prompt:manage");

        assertThatThrownBy(() -> fixture.controller.setEnabled("SQL_GEN", enabledRequest()))
                .isInstanceOf(BusinessException.class);
        verify(fixture.service, never()).setEnabled(anyString(), anyBoolean());
    }

    @Test
    void manageRightsDoNotImplyApproveRights() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        // 只有 prompt:manage：维护不自动获得审批权
        fixture.denyGlobal(7L, "prompt:approve");

        assertThatThrownBy(() -> fixture.controller.approve("SQL_GEN", null))
                .isInstanceOf(BusinessException.class);
        verify(fixture.service, never()).approve(anyString(), any());
    }

    @Test
    void viewEndpointsOnlyNeedViewRights() {
        Fixture fixture = new Fixture();
        fixture.login(7L);
        // 只有 prompt:view：查看接口可用，不需要 manage 或 approve
        fixture.denyGlobal(7L, "prompt:manage");
        fixture.denyGlobal(7L, "prompt:approve");

        fixture.controller.list(1, 20);
        fixture.controller.versions("SQL_GEN");

        verify(fixture.service).listTemplates(1, 20);
        verify(fixture.service).getVersionHistory("SQL_GEN");
    }

    @Test
    void notLoggedInNeverReachesTheService() {
        Fixture fixture = new Fixture();
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> fixture.controller.list(1, 20))
                .isInstanceOf(BusinessException.class);
        verify(fixture.service, never()).listTemplates(anyInt(), anyInt());
    }

    // ---------- 夹具 ----------

    private static PromptTemplateUpdateDTO updateRequest() {
        PromptTemplateUpdateDTO request = new PromptTemplateUpdateDTO();
        request.setContent("new content");
        return request;
    }

    private static PromptTemplateEnabledDTO enabledRequest() {
        PromptTemplateEnabledDTO request = new PromptTemplateEnabledDTO();
        request.setEnabled(false);
        return request;
    }

    private static final class Fixture {
        private final PromptTemplateService service = mock(PromptTemplateService.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final PromptTemplateController controller;

        Fixture() {
            PromptTemplateController target = new PromptTemplateController(service);
            // Prompt 三个码都是全局功能，只用到全局校验；资源解析器注册表为空即可。
            IamS1AuthorizationAspect aspect = new IamS1AuthorizationAspect(adminGuard,
                    new com.dataocean.module.permission.s1.resource.IamS1ResourceResolverRegistry(List.of()));
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            this.controller = factory.getProxy();
        }

        void denyGlobal(Long userId, String functionCode) {
            doThrow(new BusinessException(403, "没有该功能")).when(adminGuard)
                    .requireGlobalFunction(eq(userId), eq(functionCode));
        }

        void login(Long userId) {
            LoginUser loginUser = new LoginUser(userId, "tester", "password", "测试员", List.of());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
        }
    }
}
