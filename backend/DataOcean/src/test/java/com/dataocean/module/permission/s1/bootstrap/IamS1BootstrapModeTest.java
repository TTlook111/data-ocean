package com.dataocean.module.permission.s1.bootstrap;

import com.dataocean.common.config.IamS1NonBootstrapCondition;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamS1BootstrapModeTest {

    @Test
    void commandLineAndSystemPropertyStyleArgumentsEnableBootstrap() {
        assertThat(IamS1BootstrapMode.isEnabled(new String[]{"--IAM_S1_BOOTSTRAP_ENABLED=true"})).isTrue();
        assertThat(IamS1BootstrapMode.isEnabled(new String[]{"--iam.s1.bootstrap.enabled=true"})).isTrue();
        assertThat(IamS1BootstrapMode.isEnabled(new String[]{"--iam.s1.bootstrap.enabled"})).isFalse();
    }

    @Test
    void springEnvironmentReadsBothBootstrapProperties() {
        Environment environment = new MockEnvironment()
                .withProperty("iam.s1.bootstrap.enabled", "true")
                .withProperty("iam.s1.bootstrap.user-id", "12");

        assertThat(IamS1BootstrapMode.isEnabled(environment)).isTrue();
        assertThat(IamS1BootstrapMode.targetUserId(environment)).isEqualTo("12");
    }

    @Test
    void schedulingConditionRejectsBootstrapMode() {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(new MockEnvironment()
                .withProperty("iam.s1.bootstrap.enabled", "true"));

        assertThat(new IamS1NonBootstrapCondition().matches(context, mock(AnnotatedTypeMetadata.class))).isFalse();
    }

    @Test
    void environmentPostProcessorDisablesWebForConfigFileProperty() {
        SpringApplication application = mock(SpringApplication.class);
        new IamS1BootstrapEnvironmentPostProcessor().postProcessEnvironment(
                new MockEnvironment().withProperty("iam.s1.bootstrap.enabled", "true"), application);

        org.mockito.Mockito.verify(application).setWebApplicationType(
                org.springframework.boot.WebApplicationType.NONE);
    }

    @Test
    void environmentPostProcessorIsRegisteredThroughSpringFactories() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(
                EnvironmentPostProcessor.class,
                IamS1BootstrapModeTest.class.getClassLoader()))
                .contains(IamS1BootstrapEnvironmentPostProcessor.class.getName());
    }
}
