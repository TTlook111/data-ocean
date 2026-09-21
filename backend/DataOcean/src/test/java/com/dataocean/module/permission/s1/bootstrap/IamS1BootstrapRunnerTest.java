package com.dataocean.module.permission.s1.bootstrap;

import com.dataocean.module.permission.s1.service.IamS1BootstrapService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IamS1BootstrapRunnerTest {

    @Test
    void runnerUsesPropertyBasedBootstrapConfiguration() throws Exception {
        IamS1BootstrapService service = mock(IamS1BootstrapService.class);
        IamS1BootstrapRunner runner = new IamS1BootstrapRunner(
                new MockEnvironment()
                        .withProperty("iam.s1.bootstrap.enabled", "true")
                        .withProperty("iam.s1.bootstrap.user-id", "12"),
                service);

        runner.run(mock(ApplicationArguments.class));

        verify(service).bootstrap(org.mockito.ArgumentMatchers.eq(12L), anyString());
    }
}
