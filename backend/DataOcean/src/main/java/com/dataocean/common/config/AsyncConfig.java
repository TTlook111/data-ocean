package com.dataocean.common.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。
 * <p>
 * 实现 AsyncConfigurer 接口，提供默认异步执行器，
 * 避免无参 @Async 方法落到 Spring 默认的 SimpleAsyncTaskExecutor（无界线程）。
 * </p>
 * <p>
 * 线程池规划：
 * <ul>
 *   <li>taskExecutor：默认执行器，用于审计、操作日志、事件监听等轻量后台任务</li>
 *   <li>queryExecutor：查询专用执行器，用于 NL2SQL Agent 异步执行任务</li>
 * </ul>
 * </p>
 * <p>
 * 线程池参数可通过 application.yml 配置：
 * <pre>
 * dataocean:
 *   async:
 *     task:
 *       core-size: 4
 *       max-size: 8
 *       queue-capacity: 100
 *     query:
 *       core-size: 10
 *       max-size: 30
 *       queue-capacity: 50
 * </pre>
 * </p>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    // ========== 默认任务执行器参数 ==========

    @Value("${dataocean.async.task.core-size:4}")
    private int taskCoreSize;

    @Value("${dataocean.async.task.max-size:8}")
    private int taskMaxSize;

    @Value("${dataocean.async.task.queue-capacity:100}")
    private int taskQueueCapacity;

    @Value("${dataocean.async.task.await-termination-seconds:30}")
    private int taskAwaitTerminationSeconds;

    // ========== 查询执行器参数 ==========

    @Value("${dataocean.async.query.core-size:10}")
    private int queryCoreSize;

    @Value("${dataocean.async.query.max-size:30}")
    private int queryMaxSize;

    @Value("${dataocean.async.query.queue-capacity:50}")
    private int queryQueueCapacity;

    @Value("${dataocean.async.query.await-termination-seconds:60}")
    private int queryAwaitTerminationSeconds;

    /**
     * 默认异步执行器。
     * <p>
     * 用于审计日志、操作日志、事件监听等轻量后台任务。
     * 参数可通过 application.yml 的 dataocean.async.task.* 配置。
     * </p>
     *
     * @return 线程池执行器
     */
    @Override
    @Bean("taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：常驻线程，处理日常轻量任务
        executor.setCorePoolSize(taskCoreSize);
        // 最大线程数：应对突发高峰
        executor.setMaxPoolSize(taskMaxSize);
        // 队列容量：核心线程满后排队等待的任务数
        executor.setQueueCapacity(taskQueueCapacity);
        // 线程名前缀：便于日志排查和线程 dump 分析
        executor.setThreadNamePrefix("async-task-");
        // 拒绝策略：调用者线程执行兜底，防止任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭：等待任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(taskAwaitTerminationSeconds);
        executor.initialize();
        return executor;
    }

    /**
     * 查询执行专用线程池。
     * <p>
     * 专用于 NL2SQL Agent 异步执行任务，避免被审计/日志任务占满。
     * 参数可通过 application.yml 的 dataocean.async.query.* 配置。
     * </p>
     *
     * @return 线程池执行器
     */
    @Bean("queryExecutor")
    public Executor queryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：常驻线程，处理日常并发查询
        executor.setCorePoolSize(queryCoreSize);
        // 最大线程数：应对突发高峰
        executor.setMaxPoolSize(queryMaxSize);
        // 队列容量：核心线程满后排队等待的任务数
        executor.setQueueCapacity(queryQueueCapacity);
        // 线程名前缀：便于日志排查和线程 dump 分析
        executor.setThreadNamePrefix("query-agent-");
        // 拒绝策略：调用者线程执行兜底，防止任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭：等待任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(queryAwaitTerminationSeconds);
        executor.initialize();
        return executor;
    }

    /**
     * 异步方法未捕获异常处理器。
     * <p>
     * 记录错误日志，便于排查异步任务失败原因。
     * </p>
     *
     * @return 异常处理器
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) -> {
            // 使用 Slf4j 记录异常
            org.slf4j.LoggerFactory.getLogger(method.getDeclaringClass())
                .error("异步方法执行失败 method={} params={}", method.getName(), params, ex);
        };
    }
}
