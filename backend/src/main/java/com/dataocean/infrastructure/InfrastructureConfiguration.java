package com.dataocean.infrastructure;

import com.dataocean.infrastructure.security.ApiErrorWriter;
import com.dataocean.infrastructure.security.DependencyFailureFilter;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * 装配跨模块使用的配置、时间、事务和 JSON 基础能力。
 */
@Configuration
@EnableConfigurationProperties(DataOceanProperties.class)
public class InfrastructureConfiguration {

    /**
     * 会话存储失败可能发生在 MVC 之外，普通 JSON 请求需要最外层统一失败边界。
     */
    @Bean
    public FilterRegistrationBean<DependencyFailureFilter> dependencyFailures(ApiErrorWriter errors) {
        var registration = new FilterRegistrationBean<>(new DependencyFailureFilter(errors));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    /**
     * 提供统一 UTC 时钟，避免依赖宿主机默认时区。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
