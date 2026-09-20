package com.dataocean.module.permission.s1.aspect;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolverRegistry;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S1 切面的准入与解析行为。 */
class IamS1AuthorizationAspectTest {

    @AfterEach
    void clearUser() {
        com.dataocean.common.security.UserContext.clear();
    }

    // ---------- 全局功能 ----------

    @Test
    void globalFunctionDelegatesToGuard() {
        Fixture fixture = new Fixture();
        login(7L);

        fixture.aspect.checkGlobal(joinPoint("globalEndpoint", 1L), global("admin:workbench:view"));

        verify(fixture.adminGuard).requireGlobalFunction(7L, "admin:workbench:view");
    }

    @Test
    void globalFunctionRejectsUnannotatedSourceRangeCode() {
        Fixture fixture = new Fixture();
        login(7L);

        // 把源范围功能标成全局功能等于绕过负责源约束，必须在执行前直接拒绝。
        Throwable thrown = catchThrowable(() ->
                fixture.aspect.checkGlobal(joinPoint("globalEndpoint", 1L), global("datasource:view")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("范围语义不一致");
        verify(fixture.adminGuard, never()).requireGlobalFunction(any(), any());
    }

    @Test
    void globalFunctionRejectsUnknownCode() {
        Fixture fixture = new Fixture();
        login(7L);

        Throwable thrown = catchThrowable(() ->
                fixture.aspect.checkGlobal(joinPoint("globalEndpoint", 1L), global("nope:nope")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("未知功能码");
    }

    @Test
    void globalFunctionRejectsMixedGlossaryCode() {
        Fixture fixture = new Fixture();
        login(7L);

        Throwable thrown = catchThrowable(() ->
                fixture.aspect.checkGlobal(joinPoint("globalEndpoint", 1L), global("glossary:view")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("混合功能码");
    }

    // ---------- 负责源过滤列表 ----------

    @Test
    void scopedListOnlyChecksTheFunction() {
        Fixture fixture = new Fixture();
        login(7L);

        fixture.aspect.checkScopedList(joinPoint("listEndpoint"), scopedList("metadata:view"));

        // 只做功能准入：可见范围由 Service 下推，“一个负责源都没有”是合法的空页而不是拒绝。
        verify(fixture.adminGuard).requireGlobalFunction(7L, "metadata:view");
    }

    @Test
    void scopedListRejectsGlobalFunctionCode() {
        Fixture fixture = new Fixture();
        login(7L);

        Throwable thrown = catchThrowable(() ->
                fixture.aspect.checkScopedList(joinPoint("listEndpoint"), scopedList("admin:workbench:view")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
    }

    // ---------- 资源功能 ----------

    @Test
    void resourceResolvesThenChecksFunctionOnTheRealDatasource() {
        Fixture fixture = new Fixture();
        login(7L);
        when(fixture.resolver.resolve(5L)).thenReturn(IamS1ResolvedResource.of(
                IamS1ResourceType.DATASOURCE, 5L, 5L));

        fixture.aspect.checkResource(joinPoint("resourceEndpoint", 5L),
                resource("datasource:manage", "#id"));

        verify(fixture.adminGuard).requireDatasourceFunction(7L, "datasource:manage", 5L);
    }

    @Test
    void multiResourceIsRejectedWhenAnyResourceIsNotAllowed() {
        Fixture fixture = new Fixture();
        login(7L);
        when(fixture.resolver.resolve(1L)).thenReturn(IamS1ResolvedResource.of(
                IamS1ResourceType.SNAPSHOT, 1L, 11L));
        when(fixture.resolver.resolve(2L)).thenReturn(IamS1ResolvedResource.of(
                IamS1ResourceType.SNAPSHOT, 2L, 22L));
        // 第二个资源不在负责范围内
        doThrow(new BusinessException(403, "没有负责该数据源"))
                .when(fixture.adminGuard).requireDatasourceFunction(7L, "metadata:release:view", 22L);

        Throwable thrown = catchThrowable(() -> fixture.aspect.checkResource(
                joinPoint("diffEndpoint", 1L, 2L), resource("metadata:release:view", "#oldId", "#newId")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        // 任意一个无权即整体拒绝，但仍然逐个走完解析（第一个已校验）
        verify(fixture.adminGuard).requireDatasourceFunction(7L, "metadata:release:view", 11L);
    }

    @Test
    void resourceWithoutDatasourceOwnershipIsRejected() {
        Fixture fixture = new Fixture();
        login(7L);
        when(fixture.resolver.resolve(5L)).thenReturn(IamS1ResolvedResource.of(
                IamS1ResourceType.SNAPSHOT, 5L, null));

        Throwable thrown = catchThrowable(() -> fixture.aspect.checkResource(
                joinPoint("resourceEndpoint", 5L), resource("metadata:view", "#id")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("没有数据源归属");
        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), anyLong());
    }

    @Test
    void missingParameterFailsClosed() {
        Fixture fixture = new Fixture();
        login(7L);

        Throwable thrown = catchThrowable(() -> fixture.aspect.checkResource(
                joinPoint("resourceEndpoint", (Object) null), resource("metadata:view", "#id")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
    }

    @Test
    void unparseableExpressionFailsClosed() {
        Fixture fixture = new Fixture();
        login(7L);

        Throwable thrown = catchThrowable(() -> fixture.aspect.checkResource(
                joinPoint("resourceEndpoint", 5L), resource("metadata:view", "#id.boom()")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
    }

    @Test
    void expressionCannotCallBeansOrTypes() {
        Fixture fixture = new Fixture();
        login(7L);

        // 受限 SpEL：类型引用、Bean 调用、任意方法调用都必须失败。
        for (String expression : List.of("T(java.lang.Runtime).getRuntime()", "@someBean.doIt()",
                "#id.getClass()")) {
            Throwable thrown = catchThrowable(() -> fixture.aspect.checkResource(
                    joinPoint("resourceEndpoint", 5L), resource("metadata:view", expression)));
            assertThat(thrown).as("表达式必须被拒绝：" + expression).isInstanceOf(BusinessException.class);
        }
        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), anyLong());
    }

    @Test
    void dtoPropertyIsReadable() {
        Fixture fixture = new Fixture();
        login(7L);
        when(fixture.resolver.resolve(9L)).thenReturn(IamS1ResolvedResource.of(
                IamS1ResourceType.DATASOURCE, 9L, 9L));

        fixture.aspect.checkResource(joinPoint("resourceEndpoint", new Body(9L)),
                resource("datasource:manage", "#request.datasourceId"));

        verify(fixture.adminGuard).requireDatasourceFunction(7L, "datasource:manage", 9L);
    }

    @Test
    void positionalAliasWorksWhenParameterNamesAreUnavailable() {
        Fixture fixture = new Fixture();
        login(7L);
        when(fixture.resolver.resolve(3L)).thenReturn(IamS1ResolvedResource.of(
                IamS1ResourceType.DATASOURCE, 3L, 3L));

        // 用“参数名发现器返回 null”真模拟参数名不可用——
        // 原先位置别名被放在 parameterNames != null 分支里，恰好在这个场景下不生效。
        fixture.anonymousAspect.checkResource(joinPoint("resourceEndpoint", 3L),
                resource("datasource:view", "#p0"));
        verify(fixture.adminGuard).requireDatasourceFunction(7L, "datasource:view", 3L);

        // #a0 是同一位置的另一个别名
        fixture.anonymousAspect.checkResource(joinPoint("resourceEndpoint", 3L),
                resource("datasource:view", "#a0"));
        verify(fixture.adminGuard, org.mockito.Mockito.times(2))
                .requireDatasourceFunction(7L, "datasource:view", 3L);
    }

    @Test
    void namedExpressionFailsClosedWhenParameterNamesAreUnavailable() {
        Fixture fixture = new Fixture();
        login(7L);

        // 参数名不可用时 #id 无法解析：这正是必须提供 #p0 / #a0 的原因。
        Throwable thrown = catchThrowable(() -> fixture.anonymousAspect.checkResource(
                joinPoint("resourceEndpoint", 3L), resource("datasource:view", "#id")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), anyLong());
    }

    @Test
    void namedExpressionStillWorksWhenParameterNamesAreAvailable() {
        Fixture fixture = new Fixture();
        login(7L);
        when(fixture.resolver.resolve(3L)).thenReturn(IamS1ResolvedResource.of(
                IamS1ResourceType.DATASOURCE, 3L, 3L));

        fixture.aspect.checkResource(joinPoint("resourceEndpoint", 3L), resource("datasource:view", "#id"));

        verify(fixture.adminGuard).requireDatasourceFunction(7L, "datasource:view", 3L);
    }

    @Test
    void resourceAnnotationRejectsGlobalFunctionCode() {
        Fixture fixture = new Fixture();
        login(7L);

        Throwable thrown = catchThrowable(() -> fixture.aspect.checkResource(
                joinPoint("resourceEndpoint", 5L), resource("organization:user:view", "#id")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), anyLong());
    }

    @Test
    void notLoggedInIsRejected() {
        Fixture fixture = new Fixture();
        com.dataocean.common.security.UserContext.clear();

        Throwable thrown = catchThrowable(() -> fixture.aspect.checkResource(
                joinPoint("resourceEndpoint", 5L), resource("metadata:view", "#id")));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("未登录");
    }

    // ---------- 辅助 ----------

    private static void login(Long userId) {
        com.dataocean.common.security.LoginUser loginUser = new com.dataocean.common.security.LoginUser(
                userId, "tester", "password", "测试员", List.of(), List.of(), List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities()));
    }

    private static IamS1Global global(String code) {
        return annotation(IamS1Global.class, Map.of("value", code));
    }

    private static IamS1ScopedList scopedList(String code) {
        return annotation(IamS1ScopedList.class, Map.of("value", code));
    }

    private static IamS1Resource resource(String function, String... ids) {
        return annotation(IamS1Resource.class, Map.of(
                "function", function,
                "resourceType", IamS1ResourceType.DATASOURCE,
                "resourceIds", ids));
    }

    @SuppressWarnings("unchecked")
    private static <T> T annotation(Class<T> type, Map<String, Object> values) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (values.containsKey(method.getName())) {
                        return values.get(method.getName());
                    }
                    return method.getDefaultValue();
                });
    }

    private static JoinPoint joinPoint(String methodName, Object... args) {
        Method method;
        try {
            if (args.length == 1 && args[0] instanceof Body) {
                method = Sample.class.getMethod("byBody", Body.class);
            } else if (args.length == 2) {
                method = Sample.class.getMethod("twoIds", Long.class, Long.class);
            } else {
                method = Sample.class.getMethod("oneId", Long.class);
            }
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    /** 参数名稳定的样例方法（-parameters 开启）。 */
    @SuppressWarnings("unused")
    private static final class Sample {
        public void oneId(Long id) {
        }

        public void twoIds(Long oldId, Long newId) {
        }

        public void byBody(Body request) {
        }
    }

    /** 请求体样例。 */
    public static final class Body {
        private final Long datasourceId;

        Body(Long datasourceId) {
            this.datasourceId = datasourceId;
        }

        public Long getDatasourceId() {
            return datasourceId;
        }
    }

    private static final class Fixture {
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1ResourceResolver resolver = mock(IamS1ResourceResolver.class);
        private final IamS1AuthorizationAspect aspect;
        /** 参数名发现器返回 null，模拟未开 -parameters / 无调试信息的类。 */
        private final IamS1AuthorizationAspect anonymousAspect;

        Fixture() {
            when(resolver.supports()).thenReturn(IamS1ResourceType.DATASOURCE);
            IamS1ResourceResolverRegistry registry = new IamS1ResourceResolverRegistry(List.of(resolver));
            this.aspect = new IamS1AuthorizationAspect(adminGuard, registry);
            this.anonymousAspect = new IamS1AuthorizationAspect(adminGuard, registry,
                    new org.springframework.core.ParameterNameDiscoverer() {
                        @Override
                        public String[] getParameterNames(java.lang.reflect.Method method) {
                            return null;
                        }

                        @Override
                        public String[] getParameterNames(java.lang.reflect.Constructor<?> constructor) {
                            return null;
                        }
                    });
        }
    }
}
