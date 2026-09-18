package com.dataocean.module.permission.s1.support;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.vo.IamS1AuthorizationDecision;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B4：后台接口的服务端强制校验，前端隐藏按钮不能替代。 */
class IamS1AdminGuardTest {

    @Test
    void globalFunctionDenyReturns403WithChineseFunctionNameAndReason() {
        IamS1AuthorizationResolver resolver = mock(IamS1AuthorizationResolver.class);
        when(resolver.hasGlobalFunction(7L, "organization:role:view")).thenReturn(false);
        IamS1AdminGuard guard = new IamS1AdminGuard(resolver);

        Throwable thrown = catchThrowable(() -> guard.requireGlobalFunction(7L, "organization:role:view"));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(403);
        assertThat(thrown.getMessage()).contains("查看角色").contains("不包含该功能");
    }

    @Test
    void globalFunctionAllowPasses() {
        IamS1AuthorizationResolver resolver = mock(IamS1AuthorizationResolver.class);
        when(resolver.hasGlobalFunction(7L, "organization:role:view")).thenReturn(true);
        IamS1AdminGuard guard = new IamS1AdminGuard(resolver);

        assertThatCode(() -> guard.requireGlobalFunction(7L, "organization:role:view"))
                .doesNotThrowAnyException();
    }

    @Test
    void datasourceFunctionDenyExplainsSameBindingRule() {
        IamS1AuthorizationResolver resolver = mock(IamS1AuthorizationResolver.class);
        when(resolver.resolveAdminAction(7L, "security:permission:manage", 12L))
                .thenReturn(IamS1AuthorizationDecision.deny("SAME_BINDING_REQUIRED",
                        "security:permission:manage", 12L));
        IamS1AdminGuard guard = new IamS1AdminGuard(resolver);

        Throwable thrown = catchThrowable(
                () -> guard.requireDatasourceFunction(7L, "security:permission:manage", 12L));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("维护授权配置").contains("同一个角色绑定");
    }

    @Test
    void datasourceFunctionWithoutDatasourceIsRejectedAsBadRequest() {
        IamS1AdminGuard guard = new IamS1AdminGuard(mock(IamS1AuthorizationResolver.class));

        Throwable thrown = catchThrowable(
                () -> guard.requireDatasourceFunction(7L, "security:permission:view", null));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(400);
        assertThat(thrown.getMessage()).contains("缺少数据源参数");
    }
}
