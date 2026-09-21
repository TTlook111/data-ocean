package com.dataocean.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 非 bootstrap 模式才启用业务定时任务。 */
@Configuration(proxyBeanMethods = false)
@Conditional(IamS1NonBootstrapCondition.class)
@EnableScheduling
public class IamS1SchedulingConfiguration {
}
