package com.dataocean.module.iam.config;

import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.iam.service.PermissionService;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.annotation.SecurityAnnotationScanners;

/**
 * 将固定功能权限注解接入 Spring Security 的方法鉴权。
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity(prePostEnabled = false)
public class MethodPermissionConfiguration {

    /**
     * 匹配显式声明所需权限的方法，复用现有的实时核权及业务拒绝响应。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor requirePermissionAdvisor(ObjectProvider<PermissionService> permissions) {
        var pointcut = new AnnotationMatchingPointcut(null, RequirePermission.class, true);
        var scanner = SecurityAnnotationScanners.requireUnique(RequirePermission.class);

        return new AuthorizationManagerBeforeMethodInterceptor(pointcut, (authentication, invocation) -> {
            // 调用前按当前身份核权，拒绝时由统一业务异常处理器返回原有响应。
            var requirement = scanner.scan(invocation.getMethod(), AopUtils.getTargetClass(invocation.getThis()));
            permissions.getObject().require(requirement.value());
            return new AuthorizationDecision(true);
        });
    }
}
