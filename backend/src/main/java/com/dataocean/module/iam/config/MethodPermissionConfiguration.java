package com.dataocean.module.iam.config;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.iam.security.CurrentIdentityFilter;
import com.dataocean.module.iam.security.RequestPermission;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.iam.service.PermissionService;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 将接口权限声明接入 Spring Security，并阻止未声明的业务接口启动。
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity(prePostEnabled = false, proxyTargetClass = true)
public class MethodPermissionConfiguration {

    /**
     * 拦截业务控制器的 MVC 方法，进入业务前按已绑定的请求参数核权。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor requirePermissionAdvisor(ObjectProvider<PermissionService> permissions, BeanFactory beans) {
        var pointcut = new StaticMethodMatcherPointcut() {
            /**
             * 覆盖控制器的映射方法，漏标注的方法也进入默认拒绝分支。
             */
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return targetClass.getName().startsWith("com.dataocean.")
                        && AnnotatedElementUtils.hasAnnotation(targetClass, Controller.class)
                        && AnnotatedElementUtils.hasAnnotation(
                                AopUtils.getMostSpecificMethod(method, targetClass), RequestMapping.class);
            }
        };

        return new AuthorizationManagerBeforeMethodInterceptor(pointcut, (authentication, invocation) -> {
            // 使用控制器实现上的声明，缺少声明时拒绝进入业务。
            Method method = AopUtils.getMostSpecificMethod(
                    invocation.getMethod(), AopUtils.getTargetClass(invocation.getThis()));
            RequirePermission requirement = method.getAnnotation(RequirePermission.class);
            if (requirement == null) {
                // 漏写声明时拒绝直接进入接口。
                throw BizIllegalException.denied();
            }
            if (requirement.access() == RequirePermission.Access.SERVICE) {
                // 内部控制器仅接受服务过滤链已经验证的非会话主体。
                if (!(authentication.get() instanceof JwtAuthenticationToken service) || !service.isAuthenticated()) {
                    throw BizIllegalException.denied();
                }
            } else if (requirement.access() != RequirePermission.Access.PUBLIC) {
                // 仅登录的接口也要求当前应用认可的会话主体。
                UserContext.current();
            }
            for (String code : requirement.value()) {
                // 固定权限按目录同时检查对应的页面访问资格。
                permissions.getObject().require(code);
            }
            if (requirement.anyOf().length != 0) {
                // 共用选项和权限目录允许来自多个获准页面。
                permissions.getObject().requireAny(requirement.anyOf());
            }
            if (requirement.policy() != RequestPermission.class) {
                // 预览或选项等复用路径从当前请求解析明确的实际动作。
                beans.getBean(requirement.policy()).require(invocation.getArguments());
            }
            return new AuthorizationDecision(true);
        });
    }

    /**
     * 根据生产 MVC 实际注册的接口检查声明完整性和公开路径的对应关系。
     */
    @Bean
    SmartInitializingSingleton requirePermissionCoverage(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings, BeanFactory beans) {
        return () -> {
            // 公开例外仍由现有会话过滤链负责，业务控制器须显式声明同样的准入方式。
            mappings.getHandlerMethods().forEach((mapping, handler) -> {
                // 框架的错误派发等入口沿用框架配置。
                if (!handler.getBeanType().getName().startsWith("com.dataocean.")) {
                    return;
                }
                Method method = handler.getMethod();
                RequirePermission requirement = method.getAnnotation(RequirePermission.class);
                if (requirement == null
                        || !Modifier.isPublic(method.getModifiers())
                        || Modifier.isFinal(method.getModifiers())) {
                    // Spring 代理必须能拦截所有正式接口。
                    throw new IllegalStateException("接口缺少可执行的权限声明：" + handler);
                }
                boolean publicAccess = requirement.access() == RequirePermission.Access.PUBLIC;
                if (mapping.getPatternValues().stream()
                        .anyMatch(path -> path.startsWith("/internal/v1/")
                                != (requirement.access() == RequirePermission.Access.SERVICE))) {
                    // 内部服务入口与浏览器功能权限不可共用路径。
                    throw new IllegalStateException("控制器服务身份声明与路径不一致：" + handler);
                }
                if (mapping.getPatternValues().stream()
                        .anyMatch(path -> CurrentIdentityFilter.PUBLIC_PATHS.contains(path) != publicAccess)) {
                    // 每条实际业务映射的公开声明须和会话过滤链一致。
                    throw new IllegalStateException("控制器公开声明与会话公开路径不一致：" + handler);
                }
                boolean dynamic = requirement.policy() != RequestPermission.class;
                boolean configured = requirement.value().length != 0 || requirement.anyOf().length != 0 || dynamic;
                if (configured != (requirement.access() == RequirePermission.Access.PERMISSION)) {
                    // 功能权限必须声明可执行条件，公开及本人入口只声明准入方式。
                    throw new IllegalStateException("接口权限声明不完整或存在冲突：" + handler);
                }
                if (dynamic) {
                    // 在接收流量前确认请求策略可解析成唯一 Bean。
                    beans.getBean(requirement.policy());
                }
            });
        };
    }
}
