package com.dataocean.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。
 * <p>
 * 定义 queryExecutor 线程池，专用于 NL2SQL Agent 异步执行任务，
 * 避免使用 Spring 默认 SimpleAsyncTaskExecutor（无界线程）导致资源耗尽。
 * </p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 查询执行专用线程池。
     * <p>
     * 核心线程 10，最大线程 30，队列容量 50。
     * 拒绝策略为 CallerRunsPolicy：当线程池和队列都满时，
     * 由调用者线程直接执行任务，起到限流兜底作用。
     * </p>
     *
     * @return 线程池执行器
     */
    @Bean("queryExecutor")
    public Executor queryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：常驻线程，处理日常并发查询
        executor.setCorePoolSize(10);
        // 最大线程数：应对突发高峰
        executor.setMaxPoolSize(30);
        // 队列容量：核心线程满后排队等待的任务数
        executor.setQueueCapacity(50);
        // 线程名前缀：便于日志排查和线程 dump 分析
        executor.setThreadNamePrefix("query-agent-");
        // 拒绝策略：调用者线程执行兜底，防止任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
