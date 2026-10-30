package com.dataocean.module.fieldsemantic.service.impl;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class GovernanceWorkerConfig {
    @Bean("governanceExecutor")
    public ThreadPoolTaskExecutor governanceExecutor() {
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);executor.setThreadNamePrefix("governance-ai-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());executor.initialize();return executor;
    }
}
