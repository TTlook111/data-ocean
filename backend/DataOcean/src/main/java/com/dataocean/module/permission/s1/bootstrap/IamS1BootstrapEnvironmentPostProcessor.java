package com.dataocean.module.permission.s1.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 在完整 Spring Environment（包括 application.yml）准备后关闭 bootstrap 模式的 Web 入口。
 */
public class IamS1BootstrapEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (IamS1BootstrapMode.isEnabled(environment)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
    }

    @Override
    public int getOrder() {
        // 配置文件和 profile 已由前置 EnvironmentPostProcessor 加载后再判定。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
